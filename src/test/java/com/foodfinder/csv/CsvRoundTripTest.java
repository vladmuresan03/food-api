package com.foodfinder.csv;

import com.foodfinder.menu.MenuItemRepository;
import com.foodfinder.photo.Photo;
import com.foodfinder.photo.PhotoRepository;
import com.foodfinder.photo.PhotoSourceType;
import com.foodfinder.photo.PhotoStatus;
import com.foodfinder.product.ProductRepository;
import com.foodfinder.restaurant.RestaurantRepository;
import jakarta.persistence.EntityManager;
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
    @Autowired EntityManager entityManager;

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

    @Test
    void menuAssetRoundTrip() throws Exception {
        restaurantCsv.parse(new StringReader(
                "restaurant_key,name,city,status\nrtma-rest,Asset Rest,Cluj-Napoca,ACTIVE\n"), false);
        menuCsv.parse(new StringReader(
                "menu_key,restaurant_key,name,menu_type,status\nrtma-menu,rtma-rest,M,PERMANENT,PUBLISHED\n"), false);

        String csv = """
                asset_key,menu_key,asset_type,source_url,size_bytes,sha256,sort_order
                rtma-asset,rtma-menu,URL,https://example.com/menu.pdf,123456,0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef,0
                """;
        CsvImportReport r = menuAssetCsv.parse(new StringReader(csv), false);
        assertThat(r.errors()).isEmpty();
        assertThat(r.inserted()).isEqualTo(1);

        StringWriter sw = new StringWriter();
        menuAssetCsv.write(sw);
        String exported = sw.toString();
        assertThat(exported).contains("rtma-asset").contains("https://example.com/menu.pdf");

        CsvImportReport re = menuAssetCsv.parse(new StringReader(exported), false);
        assertThat(re.errors()).isEmpty();
        assertThat(re.inserted()).isZero();
        assertThat(re.updated()).isEqualTo(1);
    }

    @Test
    void photoDuplicatePrimaryInFileFailsCleanly() throws Exception {
        restaurantCsv.parse(new StringReader(
                "restaurant_key,name,city,status\nrdp-rest,Photo Rest,Cluj-Napoca,ACTIVE\n"), false);
        productCsv.parse(new StringReader(
                "product_key,restaurant_key,name,status\nrdp-prod,rdp-rest,P,ACTIVE\n"), false);

        // Two photos both claiming is_primary=true for the same product.
        // The DB's ux_photo_primary_per_product would surface this as a
        // 500; the importer must catch it first.
        String csv = """
                photo_key,restaurant_key,product_key,source_type,external_url,alt_text,is_primary,status
                rdp-photo-a,rdp-rest,rdp-prod,RESTAURANT_OFFICIAL,https://a.example/,A,true,ACTIVE
                rdp-photo-b,rdp-rest,rdp-prod,RESTAURANT_OFFICIAL,https://b.example/,B,true,ACTIVE
                """;
        CsvImportReport r = photoCsv.parse(new StringReader(csv), false);
        assertThat(r.errors()).anyMatch(e -> e.code() == CsvErrorCode.DUPLICATE_PRIMARY);
    }

    @Test
    void photoRoundTripPreservesStorageKey() throws Exception {
        // B4: a photo with storage_key set must round-trip cleanly.
        // The bug: write() emits "" for missing external_url; the DB's
        // ck_photo_storage_xor fires on re-import because empty string
        // is not NULL.
        // storage_key is export-only (per PhotoCsv.HEADERS comment), so
        // the first import goes via external_url. The test creates the
        // photo by writing it directly to the repository (simulating a
        // real upload) and then exercises the export / re-import path.
        restaurantCsv.parse(new StringReader(
                "restaurant_key,name,city,status\nrtp-rest,Round Trip,Cluj-Napoca,ACTIVE\n"), false);
        productCsv.parse(new StringReader(
                "product_key,restaurant_key,name,status\nrtp-prod,rtp-rest,P,ACTIVE\n"), false);

        long restaurantId = restaurants.findByRestaurantKey("rtp-rest").orElseThrow().getId();
        long productId = products.findByProductKey("rtp-prod").orElseThrow().getId();
        Photo seeded = new Photo();
        seeded.setPhotoKey("rtp-photo");
        seeded.setRestaurantId(restaurantId);
        seeded.setProductId(productId);
        seeded.setSourceType(PhotoSourceType.UPLOAD);
        seeded.setStorageKey("storage/rtp-photo.jpg");
        seeded.setThumbnailStorageKey("storage/rtp-photo-thumb.jpg");
        seeded.setMimeType("image/jpeg");
        seeded.setWidth(800);
        seeded.setHeight(600);
        seeded.setAltText("Round Trip Photo");
        seeded.setPrimaryPhoto(true);
        seeded.setStatus(PhotoStatus.ACTIVE);
        photos.save(seeded);
        entityManager.flush();

        StringWriter sw = new StringWriter();
        photoCsv.write(sw);
        String exported = sw.toString();

        CsvImportReport re = photoCsv.parse(new StringReader(exported), false);
        // The bug surfaces as either a parse error (when the importer
        // detects the empty string) or a constraint violation; with the
        // fix the round-trip is clean and the in-memory photo keeps
        // storage_key, external_url stays null.
        assertThat(re.errors()).isEmpty();
        entityManager.flush();
        Photo reloaded = photos.findByPhotoKey("rtp-photo").orElseThrow();
        assertThat(reloaded.getStorageKey()).isEqualTo("storage/rtp-photo.jpg");
        assertThat(reloaded.getExternalUrl()).isNull();
    }
}
