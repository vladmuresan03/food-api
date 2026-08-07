package com.foodfinder.product;

import java.util.List;
import java.util.Set;

/**
 * Computes the {@code is_vegan} / {@code is_vegetarian} / {@code is_gluten_free}
 * flags from a list of ingredients. The flags are never stored: they are
 * derived on every read from the structured ingredient list, so they
 * cannot go stale (which is the failure mode of the manual
 * "vegetarian: true" boolean on products in classic platforms).
 *
 * <p>Allergen-code based classification. We don't try to detect
 * "contains milk" from the free-text name — too brittle — but we do
 * detect every animal-derived EU Anex II code. The classification
 * assumes that any product whose ingredient list declares an
 * animal-derived allergen is not vegan (and not vegetarian, for the
 * meat/fish groups).</p>
 */
public final class DietaryClassifier {

    /**
     * "Meat" for the purpose of vegetarian classification = the EU Anex II
     * codes that are NOT tolerated by lacto-ovo vegetarians: fish,
     * crustaceans, molluscs. Eggs and milk are animal products but are
     * allowed in standard vegetarian diets.
     */
    private static final Set<String> MEAT_OR_FISH = Set.of(
            "fish", "crustaceans", "molluscs"
    );

    /**
     * "Animal-derived" for the purpose of vegan classification = every
     * Anex II code that comes from an animal. Eggs and milk qualify.
     */
    private static final Set<String> ANIMAL_DERIVED = Set.of(
            "eggs", "fish", "crustaceans", "molluscs", "milk"
    );

    /** Cereals containing gluten. EU 1169/2011 bundles them as "gluten". */
    private static final Set<String> GLUTEN = Set.of("gluten");

    private DietaryClassifier() {
    }

    public static Dtos_Dietary classify(List<ProductIngredient> ingredients) {
        if (ingredients == null || ingredients.isEmpty()) {
            // No structured list: we cannot claim a dietary preference.
            return new Dtos_Dietary(false, false, false);
        }
        boolean hasMeat = false;
        boolean hasAnimal = false;
        boolean hasGluten = false;
        for (ProductIngredient i : ingredients) {
            String code = i.getAllergenCode();
            if (code == null) {
                continue;
            }
            String norm = code.toLowerCase();
            if (MEAT_OR_FISH.contains(norm)) {
                hasMeat = true;
            }
            if (ANIMAL_DERIVED.contains(norm)) {
                hasAnimal = true;
            }
            if (GLUTEN.contains(norm)) {
                hasGluten = true;
            }
        }
        return new Dtos_Dietary(
                !hasAnimal,   // vegan: no animal product at all
                !hasMeat,     // vegetarian (lacto-ovo): no fish/crust/mollusc
                !hasGluten);
    }

    /**
     * DTO mirror, defined here to keep the classifier's signature
     * stable and the Dtos class from pulling in JPA types.
     */
    public record Dtos_Dietary(boolean vegan, boolean vegetarian, boolean glutenFree) {
    }
}
