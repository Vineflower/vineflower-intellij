package org.jetbrains.java.decompiler.main.extern;

import java.io.IOException;
import java.io.InputStream;

public interface IContextSource {
    String getName();
    Entries getEntries();
    boolean isLazy();
    boolean hasClass(final String className) throws IOException;
    byte[] getClassBytes(final String className) throws IOException;
    InputStream getInputStream(final String resource) throws IOException;

    public static final class Entries {
        public static final Entries EMPTY = new Entries();
    }
}
