package org.jetbrains.java.decompiler.main.extern;

import java.io.IOException;

// Stub class
public interface IBytecodeProvider {
    byte[] getBytecode(String externalPath, String internalPath) throws IOException;
}
