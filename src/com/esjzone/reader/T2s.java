package com.esjzone.reader;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.HashMap;

/**
 * Traditional to simplified conversion, built from OpenCC's TSCharacters table.
 *
 * <p>Char level only; the phrase dictionaries OpenCC also ships are not included, which keeps the
 * asset at ~34 KB instead of several megabytes. That matches what the mirror scripts do in
 * practice, since they run each paragraph through OpenCC's t2s profile.</p>
 */
public final class T2s {

    private final HashMap<Character, Character> table;

    private T2s(HashMap<Character, Character> table) {
        this.table = table;
    }

    public static T2s empty() {
        return new T2s(new HashMap<Character, Character>());
    }

    public static T2s load(File file) throws IOException {
        InputStream in = new FileInputStream(file);
        try {
            return load(in);
        } finally {
            in.close();
        }
    }

    public static T2s load(InputStream in) throws IOException {
        HashMap<Character, Character> table = new HashMap<Character, Character>(6000);
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, "UTF-8"), 1 << 16);
        String line;
        while ((line = reader.readLine()) != null) {
            // one "traditional<TAB>simplified" pair per line
            if (line.length() < 3 || line.charAt(1) != '\t') {
                continue;
            }
            char source = line.charAt(0);
            char target = line.charAt(2);
            // characters outside the basic plane arrive as surrogate halves and are skipped
            if (Character.isSurrogate(source) || Character.isSurrogate(target)) {
                continue;
            }
            if (source != target) {
                table.put(source, target);
            }
        }
        return new T2s(table);
    }

    public int size() {
        return table.size();
    }

    public boolean isEmpty() {
        return table.isEmpty();
    }

    public String convert(String text) {
        if (text == null || text.length() == 0 || table.isEmpty()) {
            return text == null ? "" : text;
        }
        StringBuilder sb = null;
        for (int i = 0; i < text.length(); i++) {
            Character replacement = table.get(text.charAt(i));
            if (replacement == null) {
                if (sb != null) {
                    sb.append(text.charAt(i));
                }
                continue;
            }
            if (sb == null) {
                sb = new StringBuilder(text.length() + 16);
                sb.append(text, 0, i);
            }
            sb.append(replacement.charValue());
        }
        return sb == null ? text : sb.toString();
    }
}
