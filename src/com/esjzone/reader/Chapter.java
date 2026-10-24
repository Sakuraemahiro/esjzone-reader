package com.esjzone.reader;

import java.util.ArrayList;

/** One chapter page: its paragraphs and inline illustrations, in document order. */
public final class Chapter {

    /** A paragraph of text or one illustration. */
    public static final class Block {
        public final boolean image;
        public final String value;

        private Block(boolean image, String value) {
            this.image = image;
            this.value = value;
        }

        public static Block text(String text) {
            return new Block(false, text);
        }

        public static Block image(String url) {
            return new Block(true, url);
        }
    }

    public String title = "";
    public String author = "";
    public String posted = "";
    /** True when the page needs a password or was replaced by a notice page. */
    public boolean unavailable;
    /** Human readable reason, shown in the EPUB in place of the missing text. */
    public String note = "";
    public final ArrayList<Block> blocks = new ArrayList<Block>();

    public int textLength() {
        int total = 0;
        for (int i = 0; i < blocks.size(); i++) {
            if (!blocks.get(i).image) {
                total += blocks.get(i).value.length();
            }
        }
        return total;
    }

    public int imageCount() {
        int total = 0;
        for (int i = 0; i < blocks.size(); i++) {
            if (blocks.get(i).image) {
                total++;
            }
        }
        return total;
    }
}
