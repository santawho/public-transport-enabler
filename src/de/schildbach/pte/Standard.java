/*
 * Copyright 2010-2015 the original author or authors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package de.schildbach.pte;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import javax.annotation.Nullable;

import de.schildbach.pte.dto.Product;
import de.schildbach.pte.dto.Style;
import de.schildbach.pte.dto.Style.Shape;

/**
 * @author Andreas Schildbach
 */
public class Standard {
    public static final int COLOR_BACKGROUND_HIGH_SPEED_TRAIN = Style.parseColor("#ffffff");
    public static final int COLOR_BACKGROUND_REGIONAL_TRAIN = Style.parseColor("#808080");
    public static final int COLOR_BACKGROUND_SUBURBAN_TRAIN = Style.parseColor("#006e34");
    public static final int COLOR_BACKGROUND_SUBWAY = Style.parseColor("#003090");
    public static final int COLOR_BACKGROUND_TRAM = Style.parseColor("#cc0000");
    public static final int COLOR_BACKGROUND_BUS = Style.parseColor("#993399");
    public static final int COLOR_BACKGROUND_COACH = Style.parseColor("#4CB300");
    public static final int COLOR_BACKGROUND_ON_DEMAND = Style.parseColor("#00695c");
    public static final int COLOR_BACKGROUND_FERRY = Style.parseColor("#0000ff");
    public static final int COLOR_BACKGROUND_REPLACEMENT_SERVICE = Style.parseColor("#805080");

    public static final Map<Product, Style> STYLES = new HashMap<>();

    static {
        STYLES.put(Product.HIGH_SPEED_TRAIN,
                new Style(Shape.RECT, COLOR_BACKGROUND_HIGH_SPEED_TRAIN, Style.RED, Style.RED));
        STYLES.put(Product.REGIONAL_TRAIN, new Style(Shape.RECT, COLOR_BACKGROUND_REGIONAL_TRAIN, Style.WHITE));
        STYLES.put(Product.SUBURBAN_TRAIN, new Style(Shape.CIRCLE, COLOR_BACKGROUND_SUBURBAN_TRAIN, Style.WHITE));
        STYLES.put(Product.SUBWAY, new Style(Shape.RECT, COLOR_BACKGROUND_SUBWAY, Style.WHITE));
        STYLES.put(Product.TRAM, new Style(Shape.RECT, COLOR_BACKGROUND_TRAM, Style.WHITE));
        STYLES.put(Product.BUS, new Style(Shape.ROUNDED, COLOR_BACKGROUND_BUS, Style.WHITE));
        STYLES.put(Product.COACH, new Style(Shape.ROUNDED, COLOR_BACKGROUND_COACH, Style.WHITE));
        STYLES.put(Product.ON_DEMAND, new Style(Shape.ROUNDED, COLOR_BACKGROUND_ON_DEMAND, Style.WHITE));
        STYLES.put(Product.FERRY, new Style(Shape.CIRCLE, COLOR_BACKGROUND_FERRY, Style.WHITE));
        STYLES.put(Product.REPLACEMENT_SERVICE, new Style(Shape.ROUNDED, COLOR_BACKGROUND_REPLACEMENT_SERVICE, Style.WHITE));
        STYLES.put(null, new Style(Style.DKGRAY, Style.WHITE));
    }

    private static final char STYLES_SEP = '|';

    private static boolean doNotUseSpecialLineStyles = false;
    private static boolean doPreferPredefinedLineStyles = false;

    public static void setDoNotUseSpecialLineStyles(final boolean doNotUseSpecialLineStyles) {
        Standard.doNotUseSpecialLineStyles = doNotUseSpecialLineStyles;
    }

    public static void setPreferPredefinedLineStyles(final boolean doPreferPredefinedLineStyles) {
        Standard.doPreferPredefinedLineStyles = doPreferPredefinedLineStyles;
    }

    public static Style resolveLineStyle(
            final @Nullable Map<String, Style> styles,
            final @Nullable String network,
            final @Nullable Product product,
            final @Nullable String label,
            final @Nullable Style styleFromNetwork) {
        if (!doNotUseSpecialLineStyles) {
            if (styleFromNetwork != null && !doPreferPredefinedLineStyles)
                return styleFromNetwork;
            final Style specialStyle = specialLineStyle(styles, network, product, label);
            if (specialStyle != null)
                return specialStyle;
            if (styleFromNetwork != null)
                return styleFromNetwork;
        }
        return defaultLineStyle(network, product, label);
    }

    public static Style defaultLineStyle(
            final @Nullable String network,
            final @Nullable Product product,
            final @Nullable String label) {
        return STYLES.get(product);
    }

    public static Style specialLineStyle(
            final @Nullable Map<String, Style> styles,
            final @Nullable String network,
            final @Nullable Product product,
            final @Nullable String label) {
        if (doNotUseSpecialLineStyles || styles == null || product == null)
            return null;

        Style style;

        if (label != null) {
            // check for line match
            style = specialLineStyle(styles, network, product.code + Objects.toString(label, ""));
            if (style != null)
                return style;

            // check for bus prefix (like "N" for night-bus)
            if (product == Product.BUS && label.length() > 1 && Character.isLetter(label.charAt(0))) {
                for (int n = 1; n < label.length(); ++n) {
                    if (!Character.isLetter(label.charAt(n))) {
                        // first attempt: try all prefix letters
                        if (n > 1) { // a single letter prefix is already handled below
                            style = specialLineStyle(styles, network, "B:" + label.substring(0, n));
                            if (style != null)
                                return style;
                        }
                    }
                }
                // second attempt: try first letter only
                style = specialLineStyle(styles, network, "B:" + label.charAt(0));
                if (style != null)
                    return style;
            }
        }

        // check for product match
        return specialLineStyle(styles, network, Character.toString(product.code));
    }

    private static Style specialLineStyle(
            final Map<String, Style> styles,
            final @Nullable String network,
            final String key) {
        if (network != null) {
            final Style style = styles.get(network + STYLES_SEP + key);
            if (style != null)
                return style;
        }
        return styles.get(key);
    }
}
