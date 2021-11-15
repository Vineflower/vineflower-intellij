package net.earthcomputer.quiltflowerintellij.impl;

import org.jetbrains.java.decompiler.main.extern.IBytecodeProvider;

import java.io.IOException;
import java.util.Map;

class MyBytecodeProvider implements IBytecodeProvider {
    private final Map<String, byte[]> pathMap;

    MyBytecodeProvider(Map<String, byte[]> pathMap) {
        this.pathMap = pathMap;
    }

    @Override
    public byte[] getBytecode(String externalPath, String internalPath) throws IOException {
        byte[] result = pathMap.get(externalPath);
        if (result == null) {
            throw new AssertionError(externalPath + " not in " + pathMap.keySet());
        }
        return result;
    }
}
