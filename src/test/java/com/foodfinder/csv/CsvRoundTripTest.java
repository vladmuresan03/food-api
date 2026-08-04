package com.foodfinder.csv;

import com.foodfinder.menu.MenuItemRepository;
import com.foodfinder.photo.PhotoRepository;
import com.foodfinder.product.ProductRepository;
import com.foodfinder.restaurant.RestaurantRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.io.StringReader;
import java.io.StringWriter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Round-trip and validation tests for the CSV import/export engine.
 * Each test is wrapped in a transaction that rolls back, so tests
 * do not leak state into each other.
 */
@SpringBootTest
@Transactional
class CsvRoundTripTest {

    @Autowired RestaurantRepository restaurants;
    @Autowired ProductRepository products;
    @Autowired MenuItemRepository menuItems;
    @Autowired PhotoRepository photos;

    @Autowired RestaurantCsv restaurantCsv;
    @Autowired MenuCsv menuCsv;
    @Autowired ProductCsv productCsv;
    @Autowired MenuItemCsv menuItemCsv;
    @Autowired PhotoCsv photoCsv;
    @Autowired MenuAssetCsv menuAssetCsv;

    // ------------------------------------------------------------------ restaurants

    @Test
    void restaurantsRoundTrip() throws Exception {
        String csv = """
                restaurant_key,name,website_url,address_line,city,latitude,longitude,status
                rt-big-belly,Big Belly,https://bigbelly.ro,Calea Mănăștur 68,Cluj-Napoca,46.761202,23.565204,ACTIVE
                rt-jaxx,Jaxx,,Calea Turzii 1,Cluj-Napoca,46.771309,23.584600,ACTIVE
                """;
        CsvImportReport report = restaurantCsv.parse(new StringReader(csv), false);
        assertThat(report.errors()).isEmpty();
        assertThat(report.inserted()).isEqualTo(2);

        StringWriter sw = new StringWriter();
        restaurantCsv.write(sw);
        String export = sw.toString();
        assertThat(export).contains("rt-big-belly").contains("rt-jaxx")
                .contains("46.761202").contains("ACTIVE");

        CsvImportReport re = restaurantCsv.parse(new StringReader(export), false);
        assertThat(re.errors()).isEmpty();
        assertThat(re.inserted()).isZero();
        assertThat(re.updated()).isEqualTo(2);
    }

    @Test
    void restaurantsRejectUnknownHeader() throws Exception {
        String csv = """
                restaurant_key,name,bogus_column
                rtr-belly,Big Belly,foo
                """;
        CsvImportReport report = restaurantCsv.parse(new StringReader(csv), false);
        assertThat(report.errors()).anyMatch(e -> e.code() == CsvErrorCode.UNKNOWN_HEADER);
        assertThat(report.inserted()).isZero();
        assertThat(report.updated()).isZero();
    }

    @Test
    void restaurantsRejectPartialCoordinates() throws Exception {
        String csv = """
                restaurant_key,name,city,latitude,longitude,status
                rtp-bad,Bad,Cluj-Napoca,46.7,,ACTIVE
                """;
        CsvImportReport report = restaurantCsv.parse(new StringReader(csv), false);
        assertThat(report.errors()).anyMatch(e -> e.code() == CsvErrorCode.INVALID_GEO_PAIR);
    }

    @Test
    void dryRunWritesNothing() throws Exception {
        String csv = """
                restaurant_key,name,city,status
                rtd-dry,Dry Run Rest,Cluj-Napoca,ACTIVE
                """;
        CsvImportReport report = restaurantCsv.parse(new StringReader(csv), true);
        assertThat(report.errors()).isEmpty();
        assertThat(report.dryRun()).isTrue();
        assertThat(restaurants.findByRestaurantKey("rtd-dry")).isEmpty();
    }

    @Test
    void anyRowErrorRejectsTheWholeImport() throws Exception {
        long countBefore = restaurants.count();
        String csv = """
                restaurant_key,name,city,status
                rta-good,Good,Cluj-Napoca,ACTIVE
                rta-bad,Bad,Cluj-Napoca,BOGUS
                """;
        CsvImportReport report = restaurantCsv.parse(new StringReader(csv), false);
        assertThat(report.errors()).isNotEmpty();
        // the whole import is rejected, even rows that were syntactically valid
        assertThat(restaurants.findByRestaurantKey("rta-good")).isEmpty();
        assertThat(restaurants.findByRestaurantKey("rta-bad")).isEmpty();
        assertThat(restaurants.count()).isEqualTo(countBefore);
    }

    @Test
    void emptyCellsMapToNull() throws Exception {
        // 8 fields: key,name,city,website_url,address_line,latitude,longitude,status
        String csv = "restaurant_key,name,city,website_url,address_line,latitude,longitude,status\n"
                + "rte-nulls,Nulls Place,Cluj-Napoca,,,,,\n";
        CsvImportReport report = restaurantCsv.parse(new StringReader(csv), false);
        assertThat(report.errors()).isEmpty();
        var r = restaurants.findByRestaurantKey("rte-nulls").orElseThrow();
        assertThat(r.getWebsiteUrl()).isNull();
        assertThat(r.getLatitude()).isNull();
        assertThat(r.getLongitude()).isNull();
    }

    @Test
    void bomAndWhitespaceAreTolerated() throws Exception {
        String csv = "\uFEFFrestaurant_key,name,city,status\n  rtb-bom  ,  Bomed  ,  Cluj-Napoca  ,  ACTIVE  \n";
        CsvImportReport report = restaurantCsv.parse(new StringReader(csv), false);
        assertThat(report.errors()).isEmpty();
        assertThat(restaurants.findByRestaurantKey("rtb-bom")).isPresent();
    }

    @Test
    void commasAndQuotesInDescription() throws Exception {
        String restCsv = "restaurant_key,name,city,status\n"
                + "rtc-rest,CSV Rest C,Cluj-Napoca,ACTIVE\n";
        restaurantCsv.parse(new StringReader(restCsv), false);

        String csv = "product_key,restaurant_key,name,description,weight_text,status\n"
                + "rtc-prod,rtc-rest,\"Meniu \"\"Special\"\"\",\"with, commas and \"\"quotes\"\"\",400 g,ACTIVE\n";
        CsvImportReport report = productCsv.parse(new StringReader(csv), false);
        assertThat(report.errors()).isEmpty();
        var p = products.findByProductKey("rtc-prod").orElseThrow();
        assertThat(p.getDescription()).isEqualTo("with, commas and \"quotes\"");
    }

    @Test
    void multilineDescriptionRoundTrips() throws Exception {
        String restCsv = "restaurant_key,name,city,status\n"
                + "rtm-rest,CSV Rest M,Cluj-Napoca,ACTIVE\n";
        restaurantCsv.parse(new StringReader(restCsv), false);

        String csv = "product_key,restaurant_key,name,description,weight_text,status\n"
                + "rtm-prod,rtm-rest,Multiline,\"line one\nline two\nline three\",,ACTIVE\n";
        CsvImportReport report = productCsv.parse(new StringReader(csv), false);
        assertThat(report.errors()).isEmpty();
        var p = products.findByProductKey("rtm-prod").orElseThrow();
        assertThat(p.getDescription()).contains("line one").contains("line three");
    }

    @Test
    void romanianDiacriticsRoundTrip() throws Exception {
        String csv = """
                restaurant_key,name,city,status
                rtr-diac,Restaurant Cu Diacritice Și âÎĂȚ,Cluj-Napoca,ACTIVE
                """;
        CsvImportReport report = restaurantCsv.parse(new StringReader(csv), false);
        assertThat(report.errors()).isEmpty();
        var r = restaurants.findByRestaurantKey("rtr-diac").orElseThrow();
        assertThat(r.getName()).contains("Și").contains("âÎĂȚ");
    }

    // ------------------------------------------------------------------ menus

    @Test
    void menusRequireExistingRestaurant() throws Exception {
        String csv = """
                menu_key,restaurant_key,name,menu_type,status
                rtm-orphan,no-such-restaurant,Main,PERMANENT,PUBLISHED
                """;
        CsvImportReport report = menuCsv.parse(new StringReader(csv), false);
        assertThat(report.errors()).anyMatch(e -> e.code() == CsvErrorCode.UNKNOWN_RESTAURANT);
    }

    @Test
    void menuValidityRangeEnforced() throws Exception {
        String restCsv = "restaurant_key,name,city,status\n"
                + "rtv-rest,Range Rest,Cluj-Napoca,ACTIVE\n";
        restaurantCsv.parse(new StringReader(restCsv), false);

        String csv = """
                menu_key,restaurant_key,name,menu_type,status,valid_from,valid_to
                rtv-bad,rtv-rest,Bad,PERMANENT,PUBLISHED,2026-08-10,2026-08-01
                """;
        CsvImportReport report = menuCsv.parse(new StringReader(csv), false);
        assertThat(report.errors()).anyMatch(e -> e.code() == CsvErrorCode.VALIDITY_RANGE);
    }

    // ------------------------------------------------------------------ menu_items

    @Test
    void crossRestaurantMenuItemFails() throws Exception {
        // restaurant A
        restaurantCsv.parse(new StringReader(
                "restaurant_key,name,city,status\nrxa,Rest A,Cluj-Napoca,ACTIVE\n"), false);
        // restaurant B
        restaurantCsv.parse(new StringReader(
                "restaurant_key,name,city,status\nrxb,Rest B,Cluj-Napoca,ACTIVE\n"), false);
        // product under A
        productCsv.parse(new StringReader(
                "product_key,restaurant_key,name,status\nrxa-prod,rxa,P,ACTIVE\n"), false);
        // menu under B
        menuCsv.parse(new StringReader(
                "menu_key,restaurant_key,name,menu_type,status\nrxb-menu,rxb,B Menu,PERMANENT,PUBLISHED\n"), false);

        // pairing rxb-menu with rxa-prod → cross-restaurant
        String cross = """
                menu_key,product_key,section_name,price,currency,available,sort_order
                rxb-menu,rxa-prod,Bad,29.00,RON,true,0
                """;
        CsvImportReport r = menuItemCsv.parse(new StringReader(cross), false);
        assertThat(r.errors()).anyMatch(e -> e.message().contains("different restaurant"));
    }

    @Test
    void menuItemNegativePriceFails() throws Exception {
        restaurantCsv.parse(new StringReader(
                "restaurant_key,name,city,status\nrtnp-rest,N Price,Cluj-Napoca,ACTIVE\n"), false);
        productCsv.parse(new StringReader(
                "product_key,restaurant_key,name,status\nrtnp-prod,rtnp-rest,P,ACTIVE\n"), false);
        menuCsv.parse(new StringReader(
                "menu_key,restaurant_key,name,menu_type,status\nrtnp-menu,rtnp-rest,M,PERMANENT,PUBLISHED\n"), false);

        String itemsCsv = """
                menu_key,product_key,section_name,price,currency,available,sort_order
                rtnp-menu,rtnp-prod,Altele,-1.00,RON,true,0
                """;
        CsvImportReport r = menuItemCsv.parse(new StringReader(itemsCsv), false);
        assertThat(r.errors()).anyMatch(e -> e.code() == CsvErrorCode.PRICE_NEGATIVE);
    }
}
