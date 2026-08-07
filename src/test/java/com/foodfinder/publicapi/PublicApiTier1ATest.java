package com.foodfinder.publicapi;

import com.foodfinder.IntegrationTest;
import com.foodfinder.csv.IngredientsCsv;
import com.foodfinder.csv.NutritionCsv;
import com.foodfinder.csv.ProductCsv;
import com.foodfinder.csv.RestaurantCsv;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.io.StringReader;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Public API exposure for Tier 1A: the menu detail item and the
 * product detail now carry nutrition + ingredients + computed
 * dietary. This file owns the consumer-app-visible shape; the
 * underlying entities and CSV importers are tested elsewhere.
 */
@IntegrationTest
@AutoConfigureMockMvc
@Transactional
class PublicApiTier1ATest {

    @Autowired MockMvc mvc;
    @Autowired RestaurantCsv restaurantCsv;
    @Autowired ProductCsv productCsv;
    @Autowired NutritionCsv nutritionCsv;
    @Autowired IngredientsCsv ingredientsCsv;

    @BeforeEach
    void seed() throws Exception {
        restaurantCsv.parse(new StringReader("""
                restaurant_key,name,city,latitude,longitude,status
                t1a-r,t1a R,Cluj-Napoca,46.77,23.55,ACTIVE
                """), false);
        productCsv.parse(new StringReader("""
                product_key,restaurant_key,name,weight_grams,status
                t1a-pizza,t1a-r,Pizza,400,ACTIVE
                t1a-pasta,t1a-r,Pasta,300,ACTIVE
                """), false);
        nutritionCsv.parse(new StringReader("""
                product_key,basis,energy_kcal,fat_g,sat_fat_g,carbs_g,sugars_g,protein_g,salt_g,fiber_g
                t1a-pizza,per_100g,290.00,12.00,5.00,33.00,4.00,12.00,1.40,2.5
                t1a-pasta,per_100g,160.00,1.00,0.30,31.00,1.00,5.50,0.05,1.8
                """), false);
        ingredientsCsv.parse(new StringReader("""
                product_key,position,name,is_allergen,allergen_code,percentage,origin_country
                t1a-pizza,1,Wheat flour,true,gluten,40.00,RO
                t1a-pizza,2,Tomato sauce,false,,30.00,IT
                t1a-pizza,3,Mozzarella,true,milk,20.00,RO
                t1a-pasta,1,Durum wheat,true,gluten,60.00,IT
                t1a-pasta,2,Eggs,true,eggs,10.00,RO
                t1a-pasta,3,Olive oil,false,,5.00,GR
                """), false);
    }

    @Test
    void productDetailExposesNutritionAndIngredientsAndDietary() throws Exception {
        mvc.perform(get("/api/products/t1a-pizza"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nutrition.basis").value("per_100g"))
                .andExpect(jsonPath("$.nutrition.energyKcal").value(290.00))
                .andExpect(jsonPath("$.nutrition.saltG").value(1.400))
                .andExpect(jsonPath("$.ingredients", hasSize(3)))
                .andExpect(jsonPath("$.ingredients[0].position").value(1))
                .andExpect(jsonPath("$.ingredients[0].name").value("Wheat flour"))
                .andExpect(jsonPath("$.ingredients[0].isAllergen").value(true))
                .andExpect(jsonPath("$.ingredients[0].allergenCode").value("gluten"))
                .andExpect(jsonPath("$.ingredients[0].percentage").value(40.00))
                .andExpect(jsonPath("$.ingredients[0].originCountry").value("RO"))
                // dairy + eggs? No, just dairy. Should be vegetarian (milk is OK),
                // not vegan (milk is animal), and not gluten-free (wheat flour).
                .andExpect(jsonPath("$.dietary.vegan").value(false))
                .andExpect(jsonPath("$.dietary.vegetarian").value(true))
                .andExpect(jsonPath("$.dietary.glutenFree").value(false));
    }

    @Test
    void productWithPastaExposesGlutenAndEggsInDietary() throws Exception {
        mvc.perform(get("/api/products/t1a-pasta"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nutrition.energyKcal").value(160.00))
                .andExpect(jsonPath("$.ingredients", hasSize(3)))
                // pasta has gluten (durum wheat) and eggs.
                .andExpect(jsonPath("$.dietary.vegan").value(false))
                .andExpect(jsonPath("$.dietary.vegetarian").value(true))
                .andExpect(jsonPath("$.dietary.glutenFree").value(false));
    }

    @Test
    void productWithNoNutritionReturnsNullNutritionAndEmptyIngredients() throws Exception {
        productCsv.parse(new StringReader("""
                product_key,restaurant_key,name,status
                t1a-bare,t1a-r,Bare,ACTIVE
                """), false);

        mvc.perform(get("/api/products/t1a-bare"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nutrition").doesNotExist())
                .andExpect(jsonPath("$.ingredients", hasSize(0)))
                // Without an ingredients list, all dietary flags are false
                // (we don't claim what we can't prove).
                .andExpect(jsonPath("$.dietary.vegan").value(false))
                .andExpect(jsonPath("$.dietary.vegetarian").value(false))
                .andExpect(jsonPath("$.dietary.glutenFree").value(false));
    }
}
