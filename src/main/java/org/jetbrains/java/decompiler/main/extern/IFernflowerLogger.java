package org.jetbrains.java.decompiler.main.extern;

// Stub class
public abstract class IFernflowerLogger {
    static {
        //noinspection ConstantConditions
        if (true) {
            throw new AssertionError("Stub class loaded");
        }
    }

    public abstract void writeMessage(String message, Severity severity);
    public abstract void writeMessage(String message, Severity severity, Throwable t);
    public abstract void startReadingClass(String className);
    public abstract void endReadingClass();
    public abstract void startMethod(String methodName);
    public abstract void endMethod();
    public abstract void startWriteClass(String className);
    public abstract void endWriteClass();

    public enum Severity {
        INFO, WARN, ERROR
    }
}
