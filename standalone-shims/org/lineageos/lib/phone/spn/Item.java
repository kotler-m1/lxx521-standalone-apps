package org.lineageos.lib.phone.spn;

/**
 * Temporary standalone compatibility model.
 *
 * Replace with the real LineageOS generated SPN model when its generator
 * and data schema have been ported.
 */
public class Item {
    private String name;
    private String number;
    private String categories;
    private String languages;
    private String organization;
    private String website;

    public Item() {
    }

    public Item(String name, String number, String categories, String languages) {
        this.name = name;
        this.number = number;
        this.categories = categories;
        this.languages = languages;
    }

    public String getName() {
        return name;
    }

    public String getNumber() {
        return number;
    }

    public String getCategories() {
        return categories;
    }

    public String getLanguages() {
        return languages;
    }

    public String getOrganization() {
        return organization;
    }

    public String getWebsite() {
        return website;
    }

    public void setOrganization(String organization) {
        this.organization = organization;
    }

    public void setWebsite(String website) {
        this.website = website;
    }

    public void setName(String name) {
        this.name = name;
    }

    public void setNumber(String number) {
        this.number = number;
    }

    public void setCategories(String categories) {
        this.categories = categories;
    }

    public void setLanguages(String languages) {
        this.languages = languages;
    }
}
