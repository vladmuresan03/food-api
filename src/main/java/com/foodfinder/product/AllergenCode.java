package com.foodfinder.product;

import java.util.Set;

/**
 * The 14 allergens that EU Regulation 1169/2011 Anex II requires to be
 * emphasised in any ingredients list. Stored as free text in
 * {@code product_ingredient.allergen_code} so the list can grow without a
 * Flyway migration; the constant list here is the application-layer
 * allowlist that the CSV importer, the admin REST, and the form all
 * validate against.
 *
 * <p>The enum name is the canonical, human-friendly label. The
 * {@link #code} string is what we persist (and what the consumer app
 * keys off when rendering the bold / icon overlay).</p>
 */
public enum AllergenCode {

    GLUTEN("gluten", "Cereale care contin gluten (grau, secara, orz, ovaz, spelta, khomasan)"),
    CRUSTACEANS("crustaceans", "Crustacee"),
    EGGS("eggs", "Ou"),
    FISH("fish", "Peste"),
    PEANUTS("peanuts", "Arahide"),
    SOYBEANS("soybeans", "Soia"),
    MILK("milk", "Lapte"),
    NUTS("nuts", "Fructe cu coaja lemnoasa (migdale, alune, nuci, caju, fistic, etc.)"),
    CELERY("celery", "Telina"),
    MUSTARD("mustard", "Mustar"),
    SESAME("sesame", "Susan"),
    SULPHITES("sulphites", "Sulfiti"),
    LUPIN("lupin", "Lupin"),
    MOLLUSCS("molluscs", "Moluște");

    /** The string stored in {@code product_ingredient.allergen_code}. */
    private final String code;

    /** Human-friendly description (Romanian, used in admin dropdowns). */
    private final String description;

    AllergenCode(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public String getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }

    /** Set of all canonical codes, used by the allowlist check. */
    public static final Set<String> ALL_CODES = Set.of(
            "gluten", "crustaceans", "eggs", "fish", "peanuts", "soybeans",
            "milk", "nuts", "celery", "mustard", "sesame", "sulphites",
            "lupin", "molluscs"
    );

    /**
     * Resolve a stored code to its enum constant, or {@code null} if it
     * is not in the canonical list. Unknown codes are tolerated (the
     * column is free text) but should be treated as "unverified" by
     * the consumer UI.
     */
    public static AllergenCode fromCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        for (AllergenCode c : values()) {
            if (c.code.equalsIgnoreCase(code)) {
                return c;
            }
        }
        return null;
    }
}
