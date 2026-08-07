package com.foodfinder.product;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the computed dietary flags. These are pure-function
 * (no DB), so they don't need the {@code @IntegrationTest} bootstrap.
 */
class DietaryClassifierTest {

    private static ProductIngredient ingredient(String name, String allergenCode) {
        return ingredient(name, allergenCode, false, null);
    }

    private static ProductIngredient ingredient(String name, String allergenCode,
                                                boolean isAllergen, BigDecimal percentage) {
        ProductIngredient pi = new ProductIngredient();
        pi.setName(name);
        pi.setAllergen(isAllergen);
        pi.setAllergenCode(allergenCode);
        pi.setPercentage(percentage);
        return pi;
    }

    @Test
    void emptyIngredientListYieldsAllFalse() {
        DietaryClassifier.Dtos_Dietary d = DietaryClassifier.classify(List.of());
        assertThat(d.vegan()).isFalse();
        assertThat(d.vegetarian()).isFalse();
        assertThat(d.glutenFree()).isFalse();
    }

    @Test
    void plainTomatoPastaIsVeganVegetarianAndGlutenFree() {
        // "Tomato sauce 70%" with "Olive oil", "Basil", "Salt" - all not allergens.
        var d = DietaryClassifier.classify(List.of(
                ingredient("Tomato sauce", null),
                ingredient("Olive oil", null),
                ingredient("Basil", null),
                ingredient("Salt", null)));
        assertThat(d.vegan()).isTrue();
        assertThat(d.vegetarian()).isTrue();
        assertThat(d.glutenFree()).isTrue();
    }

    @Test
    void wheatFlourRemovesGlutenFree() {
        var d = DietaryClassifier.classify(List.of(
                ingredient("Wheat flour", "gluten"),
                ingredient("Water", null),
                ingredient("Salt", null)));
        assertThat(d.glutenFree()).isFalse();
        assertThat(d.vegan()).isTrue();
        assertThat(d.vegetarian()).isTrue();
    }

    @Test
    void milkIsNotVeganButIsVegetarian() {
        var d = DietaryClassifier.classify(List.of(
                ingredient("Mozzarella", "milk"),
                ingredient("Tomato sauce", null)));
        assertThat(d.vegan()).isFalse();
        assertThat(d.vegetarian()).isTrue();
        assertThat(d.glutenFree()).isTrue();
    }

    @Test
    void eggsAreNotVeganButAreVegetarian() {
        var d = DietaryClassifier.classify(List.of(
                ingredient("Eggs", "eggs"),
                ingredient("Flour", "gluten")));
        assertThat(d.vegan()).isFalse();
        assertThat(d.vegetarian()).isTrue();
        assertThat(d.glutenFree()).isFalse();
    }

    @Test
    void fishCrustaceansAndMolluscsAreNotVegetarian() {
        for (String code : List.of("fish", "crustaceans", "molluscs")) {
            var d = DietaryClassifier.classify(List.of(
                    ingredient("Tuna", code),
                    ingredient("Olive oil", null)));
            assertThat(d.vegan()).as("vegan for %s", code).isFalse();
            assertThat(d.vegetarian()).as("vegetarian for %s", code).isFalse();
        }
    }

    @Test
    void nonAllergenNamesAreIgnored() {
        // The classifier keys off allergen_code, not the free-text name.
        // This is intentional: a name-based check would be brittle and
        // would force us to maintain a synonym dictionary.
        var d = DietaryClassifier.classify(List.of(
                ingredient("Some mystery ingredient with meat in it", null),
                ingredient("Onion", null)));
        assertThat(d.vegan()).isTrue();
        assertThat(d.vegetarian()).isTrue();
    }
}
