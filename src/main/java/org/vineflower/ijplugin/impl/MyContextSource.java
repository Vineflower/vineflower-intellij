package org.vineflower.ijplugin.impl;

import org.jetbrains.java.decompiler.main.extern.IContextSource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.function.Function;
import java.util.function.Predicate;

public class MyContextSource implements IContextSource {
    private final Predicate<String> hasClass;
    private final Function<String, byte[]> getClassBytes;

    MyContextSource(Predicate<String> hasClass, Function<String, byte[]> getClassBytes) {
        this.hasClass = hasClass;
        this.getClassBytes = getClassBytes;
    }

    @Override
    public String getName() {
        return "IntelliJ Classes Source";
    }

    @Override
    public Entries getEntries() {
        return Entries.EMPTY;
    }

    @Override
    public boolean isLazy() {
        return true;
    }

    @Override
    public boolean hasClass(String className) {
        return hasClass.test(className);
    }

    @Override
    public byte[] getClassBytes(String className) {
        return getClassBytes.apply(className);
    }

    @Override
    public InputStream getInputStream(String resource) throws IOException {
        if (resource.endsWith(".class")) {
            return new ByteArrayInputStream(getClassBytes(resource.substring(0, resource.length() - 6)));
        }
        return null;
    }
}
