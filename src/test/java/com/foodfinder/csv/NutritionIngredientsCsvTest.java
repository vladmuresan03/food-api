package com.foodfinder.csv;

import com.foodfinder.IntegrationTest;
import com.foodfinder.product.AllergenCode;
import com.foodfinder.product.Product;
import com.foodfinder.product.ProductIngredient;
import com.foodfinder.product.ProductIngredientRepository;
import com.foodfinder.product.ProductNutrition;
import com.foodfinder.product.ProductNutritionRepository;
import com.foodfinder.product.ProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.io.StringReader;
import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Round-trip + error path tests for the two new Tier 1A CSV
 * importers. The public API exposure is tested separately in
 * {@link com.foodfinder.publicapi.PublicApiTest}.
 */
@IntegrationTest
@Transactional
class NutritionIngredientsCsvTest {

    @Autowired ProductRepository products;
    @Autowired ProductNutritionRepository nutritions;
    @Autowired ProductIngredientRepository ingredients;
    @Autowired NutritionCsv nutritionCsv;
    @Autowired IngredientsCsv ingredientsCsv;
    @Autowired RestaurantCsv restaurantCsv;
    @Autowired ProductCsv productCsv;

    @BeforeEach
    void seed() throws Exception {
        restaurantCsv.parse(new StringReader("""
                restaurant_key,name,city,status
                csv-nr,CSV NR,Cluj-Napoca,ACTIVE
                """), false);
        productCsv.parse(new StringReader("""
                product_key,restaurant_key,name,weight_grams,status
                csv-pizza,csv-nr,Pizza,400,ACTIVE
                csv-pasta,csv-nr,Pasta,300,ACTIVE
                """), false);
    }

    // -------------------------------------------------------------- NutritionCsv

    @Test
    void nutritionCsvRoundtrip() throws Exception {
        nutritionCsv.parse(new StringReader("""
                product_key,basis,energy_kcal,fat_g,sat_fat_g,carbs_g,sugars_g,protein_g,salt_g,fiber_g,source_url,last_verified_at
                csv-pizza,per_100g,290.00,12.00,5.00,33.00,4.00,12.00,1.40,2.5,https://example.com/pizza.pdf,2026-08-01
                """), false);

        ProductNutrition n = nutritions.findById(
                products.findByProductKey("csv-pizza").orElseThrow().getId()).orElseThrow();
        assertThat(n.getBasis()).isEqualTo("per_100g");
        assertThat(n.getEnergyKcal()).isEqualByComparingTo("290.00");
        assertThat(n.getFatG()).isEqualByComparingTo("12.00");
        assertThat(n.getSatFatG()).isEqualByComparingTo("5.00");
        assertThat(n.getCarbsG()).isEqualByComparingTo("33.00");
        assertThat(n.getSugarsG()).isEqualByComparingTo("4.00");
        assertThat(n.getProteinG()).isEqualByComparingTo("12.00");
        assertThat(n.getSaltG()).isEqualByComparingTo("1.400");
        assertThat(n.getFiberG()).isEqualByComparingTo("2.50");
        assertThat(n.getSourceUrl()).isEqualTo("https://example.com/pizza.pdf");
        assertThat(n.getLastVerifiedAt()).isNotNull();
    }

    @Test
    void nutritionCsvRejectsUnknownProduct() throws Exception {
        CsvImportReport r = nutritionCsv.parse(new StringReader("""
                product_key,energy_kcal
                ghost-product,100
                """), false);
        assertThat(r.errors()).isNotEmpty();
        assertThat(r.errors().get(0).code()).isEqualTo(CsvErrorCode.UNKNOWN_PRODUCT);
    }

    @Test
    void nutritionCsvRejectsInvalidBasis() throws Exception {
        CsvImportReport r = nutritionCsv.parse(new StringReader("""
                product_key,basis,energy_kcal
                csv-pizza,per_gram,100
                """), false);
        assertThat(r.errors()).isNotEmpty();
        assertThat(r.errors().get(0).code()).isEqualTo(CsvErrorCode.INVALID_BASIS);
    }

    @Test
    void nutritionCsvRejectsNegativeEnergy() throws Exception {
        CsvImportReport r = nutritionCsv.parse(new StringReader("""
                product_key,energy_kcal
                csv-pizza,-10
                """), false);
        assertThat(r.errors()).isNotEmpty();
        assertThat(r.errors().get(0).code()).isEqualTo(CsvErrorCode.INVALID_NUMBER);
    }

    // -------------------------------------------------------------- IngredientsCsv

    @Test
    void ingredientsCsvInsertPreservesOrder() throws Exception {
        ingredientsCsv.parse(new StringReader("""
                product_key,position,name,is_allergen,allergen_code,percentage,origin_country
                csv-pizza,1,Wheat flour,true,gluten,40.00,RO
                csv-pizza,2,Tomato sauce,false,,30.00,IT
                csv-pizza,3,Mozzarella,true,milk,20.00,RO
                csv-pizza,4,Olive oil,false,,5.00,GR
                """), false);

        Long productId = products.findByProductKey("csv-pizza").orElseThrow().getId();
        List<ProductIngredient> rows = ingredients.findByIdProductIdOrderByIdPositionAsc(productId);
        assertThat(rows).hasSize(4);
        assertThat(rows.get(0).getName()).isEqualTo("Wheat flour");
        assertThat(rows.get(0).isAllergen()).isTrue();
        assertThat(rows.get(0).getAllergenCode()).isEqualTo("gluten");
        assertThat(rows.get(0).getPercentage()).isEqualByComparingTo("40.00");
        assertThat(rows.get(0).getOriginCountry()).isEqualTo("RO");
        assertThat(rows.get(2).getAllergenCode()).isEqualTo("milk");
    }

    @Test
    void ingredientsCsvRejectsUnknownAllergenCode() throws Exception {
        CsvImportReport r = ingredientsCsv.parse(new StringReader("""
                product_key,position,name,is_allergen,allergen_code
                csv-pizza,1,Plastic,true,rubber
                """), false);
        assertThat(r.errors()).isNotEmpty();
        assertThat(r.errors().get(0).code()).isEqualTo(CsvErrorCode.INVALID_ALLERGEN);
        assertThat(r.errors().get(0).message()).contains("rubber");
    }

    @Test
    void ingredientsCsvRejectsMismatchBetweenIsAllergenAndCode() throws Exception {
        // is_allergen=true but no code
        CsvImportReport r1 = ingredientsCsv.parse(new StringReader("""
                product_key,position,name,is_allergen
                csv-pizza,1,Eggs,true
                """), false);
        assertThat(r1.errors()).isNotEmpty();

        // code set but is_allergen=false
        CsvImportReport r2 = ingredientsCsv.parse(new StringReader("""
                product_key,position,name,is_allergen,allergen_code
                csv-pizza,1,Eggs,false,eggs
                """), false);
        assertThat(r2.errors()).isNotEmpty();
    }

    @Test
    void ingredientsCsvRejectsInvalidPercentage() throws Exception {
        CsvImportReport r = ingredientsCsv.parse(new StringReader("""
                product_key,position,name,percentage
                csv-pizza,1,Flour,150.00
                """), false);
        assertThat(r.errors()).isNotEmpty();
        assertThat(r.errors().get(0).code()).isEqualTo(CsvErrorCode.INVALID_PERCENTAGE);
    }

    @Test
    void ingredientsCsvRejectsInvalidCountry() throws Exception {
        CsvImportReport r = ingredientsCsv.parse(new StringReader("""
                product_key,position,name,origin_country
                csv-pizza,1,Beef,ROM
                """), false);
        assertThat(r.errors()).isNotEmpty();
        assertThat(r.errors().get(0).code()).isEqualTo(CsvErrorCode.INVALID_COUNTRY);
    }

    @Test
    void ingredientsCsvRejectsDuplicatePosition() throws Exception {
        CsvImportReport r = ingredientsCsv.parse(new StringReader("""
                product_key,position,name
                csv-pizza,1,Flour
                csv-pizza,1,Water
                """), false);
        assertThat(r.errors()).isNotEmpty();
        assertThat(r.errors().get(0).code()).isEqualTo(CsvErrorCode.DUPLICATE_KEY_IN_FILE);
    }

    @Test
    void ingredientsCsvReplaceAllDeletesPreviousRows() throws Exception {
        ingredientsCsv.parse(new StringReader("""
                product_key,position,name
                csv-pasta,1,Flour
                csv-pasta,2,Eggs
                csv-pasta,3,Salt
                """), false);

        Long productId = products.findByProductKey("csv-pasta").orElseThrow().getId();
        assertThat(ingredients.findByIdProductIdOrderByIdPositionAsc(productId)).hasSize(3);

        // Resend with fewer rows; the previous 3 must be wiped.
        ingredientsCsv.parse(new StringReader("""
                product_key,position,name
                csv-pasta,1,NewFlour
                """), false);
        List<ProductIngredient> rows = ingredients.findByIdProductIdOrderByIdPositionAsc(productId);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getName()).isEqualTo("NewFlour");
    }

    @Test
    void allergenCodeAllowlistMatchesEUUnexIINames() {
        // The allowlist must include exactly the 14 EU Anex II codes.
        // If you intentionally add or remove a code, update both this
        // test and the enum.
        assertThat(AllergenCode.ALL_CODES).hasSize(14);
        assertThat(AllergenCode.ALL_CODES).containsExactlyInAnyOrder(
                "gluten", "crustaceans", "eggs", "fish", "peanuts", "soybeans",
                "milk", "nuts", "celery", "mustard", "sesame", "sulphites",
                "lupin", "molluscs");
    }
}
