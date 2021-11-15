package net.earthcomputer.quiltflowerintellij.impl;

import org.jetbrains.java.decompiler.main.extern.IFernflowerLogger;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

class MyLogger extends IFernflowerLogger {
    private final Consumer<String> errorConsumer;
    private final BiConsumer<String, Throwable> warningConsumer;
    private final BiConsumer<String, Throwable> infoConsumer;
    private final BiConsumer<String, Throwable> debugConsumer;
    private final Consumer<Throwable> processCanceledExceptionThrower;

    private String myClass;

    MyLogger(
            Consumer<String> errorConsumer,
            BiConsumer<String, Throwable> warningConsumer,
            BiConsumer<String, Throwable> infoConsumer,
            BiConsumer<String, Throwable> debugConsumer,
            Consumer<Throwable> processCanceledExceptionThrower
    ) {
        this.errorConsumer = errorConsumer;
        this.warningConsumer = warningConsumer;
        this.infoConsumer = infoConsumer;
        this.debugConsumer = debugConsumer;
        this.processCanceledExceptionThrower = processCanceledExceptionThrower;
    }

    @Override
    public void writeMessage(String message, Severity severity) {
        String text = extendMessage(message);
        switch (severity) {
            case ERROR: errorConsumer.accept(text); break;
            case WARN: warningConsumer.accept(text, null); break;
            case INFO: infoConsumer.accept(text, null); break;
            default: debugConsumer.accept(text, null); break;
        }
    }

    @Override
    public void writeMessage(String message, Severity severity, Throwable t) {
        if (t instanceof InternalException) {
            throw (InternalException) t;
        }
        if (t.getClass().getName().equals("com.intellij.openapi.progress.ProcessCanceledException")) {
            throw (RuntimeException) t;
        }
        if (t instanceof InterruptedException) {
            processCanceledExceptionThrower.accept(t);
        }

        String text = extendMessage(message);
        switch (severity) {
            case ERROR: throw new InternalException(text, t);
            case WARN: warningConsumer.accept(text, t); break;
            case INFO: infoConsumer.accept(text, t); break;
            default: debugConsumer.accept(text, t); break;
        }
    }

    private String extendMessage(String message) {
        if (myClass != null) {
            return message + "[" + myClass + "]";
        } else {
            return message;
        }
    }

    @Override
    public void startReadingClass(String className) {
        debugConsumer.accept("decompiling class " + className, null);
        myClass = className;
    }

    @Override
    public void endReadingClass() {
        debugConsumer.accept("... class decompiled", null);
        myClass = null;
    }

    @Override
    public void startMethod(String methodName) {
        debugConsumer.accept("processing method " + methodName, null);
    }

    @Override
    public void endMethod() {
        debugConsumer.accept("... method processed", null);
    }

    @Override
    public void startWriteClass(String className) {
        debugConsumer.accept("writing class " + className, null);
    }

    @Override
    public void endWriteClass() {
        debugConsumer.accept("... class written", null);
    }

    public static final class InternalException extends RuntimeException {
        public InternalException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
