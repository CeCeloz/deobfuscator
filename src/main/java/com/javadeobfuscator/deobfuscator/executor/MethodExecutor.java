/*
 * Copyright 2016 Sam Sun <me@samczsun.com>
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package com.javadeobfuscator.deobfuscator.executor;

import static com.javadeobfuscator.deobfuscator.org.objectweb.asm.Opcodes.*;

import java.lang.reflect.Array;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.function.BiFunction;

import com.google.common.base.Optional;
import com.javadeobfuscator.deobfuscator.executor.defined.types.JavaClass;
import com.javadeobfuscator.deobfuscator.executor.exceptions.*;
import com.javadeobfuscator.deobfuscator.executor.values.JavaAddress;
import com.javadeobfuscator.deobfuscator.executor.values.JavaBoolean;
import com.javadeobfuscator.deobfuscator.executor.values.JavaByte;
import com.javadeobfuscator.deobfuscator.executor.values.JavaCharacter;
import com.javadeobfuscator.deobfuscator.executor.values.JavaDouble;
import com.javadeobfuscator.deobfuscator.executor.values.JavaFloat;
import com.javadeobfuscator.deobfuscator.executor.values.JavaInteger;
import com.javadeobfuscator.deobfuscator.executor.values.JavaLong;
import com.javadeobfuscator.deobfuscator.executor.values.JavaObject;
import com.javadeobfuscator.deobfuscator.executor.values.JavaShort;
import com.javadeobfuscator.deobfuscator.executor.values.JavaTop;
import com.javadeobfuscator.deobfuscator.executor.values.JavaValue;
import com.javadeobfuscator.deobfuscator.org.objectweb.asm.Type;
import com.javadeobfuscator.deobfuscator.org.objectweb.asm.tree.*;
import com.javadeobfuscator.deobfuscator.utils.PrimitiveUtils;
import com.javadeobfuscator.deobfuscator.utils.Utils;
import com.javadeobfuscator.deobfuscator.utils.WrappedClassNode;

public class MethodExecutor {
    private static final boolean VERIFY;
    private static final boolean DEBUG;
    private static final boolean DEBUG_PRINT_EXCEPTIONS;
    private static final List<String> DEBUG_CLASSES;
    private static final List<String> DEBUG_METHODS_WITH_DESC;

    static {
        VERIFY = false;
        DEBUG = false;
        DEBUG_PRINT_EXCEPTIONS = false;
        DEBUG_CLASSES = Arrays.asList();
        DEBUG_METHODS_WITH_DESC = Arrays.asList();
    }

    public static <T> T execute(WrappedClassNode classNode, MethodNode method, List<JavaValue> args, Object instance, Context context) {
        if (context == null)
            throw new IllegalArgumentException("Null context");
        List<JavaValue> stack = new LinkedList<>();
        List<JavaValue> locals = new LinkedList<>();
        if (!Modifier.isStatic(method.access)) {
            if (instance != null && instance instanceof JavaObject) {
                locals.add((JavaObject) instance);
            } else {
                locals.add(new JavaObject(instance, "java/lang/Object"));
            }
        }
        if (args != null) {
            for (JavaValue arg : args) {
                locals.add(arg.copy());
                if (arg instanceof JavaDouble || arg instanceof JavaLong) {
                    locals.add(new JavaTop());
                }
            }
        }
        for (int i = 0; i < method.maxLocals + 50; i++) {
            locals.add(null);
        }
        return execute(classNode, method, method.instructions.getFirst(), stack, locals, context);
    }

    private static void executeArrayLoad(List<JavaValue> stack, Class<?> type) {
        if (VERIFY && stack.size() < 2) {
            throw new ExecutionException("Stack underflow");
        }
        if (VERIFY && !(stack.get(0) instanceof JavaInteger)) {
            throw new ExecutionException("Expected Integer on stack");
        }
        if (VERIFY && !(stack.get(1) instanceof JavaObject)) {
            throw new ExecutionException("Expected Object on stack");
        }
        int index = stack.remove(0).intValue();
        Object array = stack.remove(0).value();
        if (VERIFY && array == null) {
            throw new ExecutionException("Array is null");
        }
        if (VERIFY && !array.getClass().isArray()) {
            throw new ExecutionException("Expected Array on stack");
        }
        Object value = Array.get(array, index);
        JavaValue result;
        switch (type.getCanonicalName()) {
            case "byte":
                if (value instanceof Byte)
                    result = new JavaByte((Byte) value);
                else if (value instanceof Boolean)
                    result = new JavaBoolean((Boolean) value);
                else
                    throw new IllegalStateException(value.getClass().getName());
                break;
            case "char":
                result = new JavaCharacter((Character) value);
                break;
            case "double":
                result = new JavaDouble((Double) value);
                break;
            case "float":
                result = new JavaFloat((Float) value);
                break;
            case "int":
                result = new JavaInteger((Integer) value);
                break;
            case "long":
                result = new JavaLong((Long) value);
                break;
            case "short":
                result = new JavaShort((Short) value);
                break;
            default:
                result = new JavaObject(value, "java/lang/Object");
                break;
        }
        stack.add(0, result);
        if (result instanceof JavaDouble || result instanceof JavaLong) {
            stack.add(0, new JavaTop());
        }
    }

    private static void executeArrayStore(List<JavaValue> stack) {
        if (VERIFY && stack.size() < 3) {
            throw new ExecutionException("Stack underflow");
        }
        JavaValue value = stack.remove(0);
        if (value instanceof JavaTop) {
            value = stack.remove(0);
            if (VERIFY && !(value instanceof JavaDouble) && !(value instanceof JavaLong)) {
                throw new ExecutionException("JavaTop not followed by JavaLong or JavaDouble");
            }
        }
        JavaValue index = stack.remove(0);
        if (VERIFY && !(index instanceof JavaInteger)) {
            throw new ExecutionException("Expected Integer");
        }
        JavaValue array = stack.remove(0);
        if (VERIFY && !array.value().getClass().isArray()) {
            throw new ExecutionException("Expected Array");
        }
        Object val = value(value);
        if (array.value() instanceof boolean[]) {
            if (value instanceof JavaInteger) {
                Array.set(array.value(), index.intValue(), value.intValue() != 0);
            } else {
                Array.set(array.value(), index.intValue(), val);
            }
        } else if(array.value() instanceof char[]) {
    		//We have to unbox everything or it throws an exception
        	if(value instanceof JavaInteger)
        		Array.set(array.value(), index.intValue(), (char)value.intValue());
        	else if(value instanceof JavaByte)
        		Array.set(array.value(), index.intValue(), (char)((JavaByte)value).byteValue());
        	else if(value instanceof JavaShort)
        		Array.set(array.value(), index.intValue(), (char)((JavaShort)value).shortValue());
        	else if(value instanceof JavaLong)
        		Array.set(array.value(), index.intValue(), (char)(value.longValue()));
        	else
        		Array.set(array.value(), index.intValue(), val);
        } else
    		Array.set(array.value(), index.intValue(), val);
    }

    public static Object convert(Object value, String type) {
        if (value instanceof Number && Utils.isDigit(type)) {
            Number cast = (Number) value;
            switch (type) {
                case "I":
                    return cast.intValue();
                case "S":
                    return cast.shortValue();
                case "B":
                    return cast.byteValue();
                case "J":
                    return cast.longValue();
            }
        }

        return value;
    }

    public static Object value(JavaValue value) {
        Object val;

        switch (value.getClass().getSimpleName()) {
            case "JavaByte":
                val = ((JavaByte) value).byteValue();
                break;
            case "JavaCharacter":
                val = ((JavaCharacter) value).charValue();
                break;
            case "JavaShort":
                val = ((JavaShort) value).shortValue();
                break;
            case "JavaInteger":
                val = value.intValue();
                break;
            case "JavaLong":
                val = value.longValue();
                break;
            case "JavaFloat":
                val = value.floatValue();
                break;
            case "JavaDouble":
                val = value.doubleValue();
                break;
            case "JavaObject":
                val = value.value();
                break;
            default:
                throw new ExecutionException("Unknown value type " + value.getClass().getSimpleName());
        }
        return val;
    }

    private static void doIntegerMath(List<JavaValue> stack, BiFunction<Integer, Integer, Integer> action) {
        if (VERIFY && stack.size() < 2) {
            throw new ExecutionException("Stack underflow");
        }
        JavaValue b = stack.remove(0);
        JavaValue a = stack.remove(0);
        stack.add(0, new JavaInteger(action.apply(a.intValue(), b.intValue())));
    }

    private static void doLongMath(List<JavaValue> stack, BiFunction<Long, Long, Long> action) {
        if (stack.size() < 4) {
            throw new ExecutionException("Stack underflow");
        }
        stack.remove(0); //top
        JavaValue b = stack.remove(0);
        stack.remove(0); //top
        JavaValue a = stack.remove(0);
        if (!(a instanceof JavaLong) || !(b instanceof JavaLong)) {
            throw new ExecutionException("Expected two JavaLongs");
        }
        stack.add(0, new JavaLong(action.apply(a.longValue(), b.longValue())));
        stack.add(0, new JavaTop());
    }

    private static void doLongShift(List<JavaValue> stack, BiFunction<Long, Integer, Long> action) {
        if (stack.size() < 3) {
            throw new ExecutionException("Stack underflow");
        }
        JavaValue b = stack.remove(0);
        stack.remove(0); //top
        JavaValue a = stack.remove(0);
        stack.add(0, new JavaLong(action.apply(a.longValue(), b.intValue())));
        stack.add(0, new JavaTop());
    }

    private static void doLongMathReturnInteger(List<JavaValue> stack, BiFunction<Long, Long, Integer> action) {
        if (VERIFY && stack.size() < 4) {
            throw new ExecutionException("Stack underflow");
        }
        stack.remove(0); //top
        JavaValue b = stack.remove(0);
        stack.remove(0); //top
        JavaValue a = stack.remove(0);
        stack.add(0, new JavaInteger(action.apply(a.longValue(), b.longValue())));
    }

    private static void doDoubleMath(List<JavaValue> stack, BiFunction<Double, Double, Double> action) {
        if (VERIFY && stack.size() < 4) {
            throw new ExecutionException("Stack underflow");
        }
        stack.remove(0); //top
        JavaValue b = stack.remove(0);
        stack.remove(0); //top
        JavaValue a = stack.remove(0);
        stack.add(0, new JavaDouble(action.apply(a.doubleValue(), b.doubleValue())));
        stack.add(0, new JavaTop());
    }

    private static void doDoubleMathReturnInteger(List<JavaValue> stack, BiFunction<Double, Double, Integer> action) {
        if (VERIFY && stack.size() < 4) {
            throw new ExecutionException("Stack underflow");
        }
        stack.remove(0); //top
        JavaValue b = stack.remove(0);
        stack.remove(0); //top
        JavaValue a = stack.remove(0);
        stack.add(0, new JavaInteger(action.apply(a.doubleValue(), b.doubleValue())));
    }

    private static void doFloatMath(List<JavaValue> stack, BiFunction<Float, Float, Float> action) {
        if (VERIFY && stack.size() < 2) {
            throw new ExecutionException("Stack underflow");
        }
        JavaValue b = stack.remove(0);
        JavaValue a = stack.remove(0);
        stack.add(0, new JavaFloat(action.apply(a.floatValue(), b.floatValue())));
    }

    private static void doFloatMathReturnInteger(List<JavaValue> stack, BiFunction<Float, Float, Integer> action) {
        if (VERIFY && stack.size() < 2) {
            throw new ExecutionException("Stack underflow");
        }
        JavaValue b = stack.remove(0);
        JavaValue a = stack.remove(0);
        stack.add(0, new JavaInteger(action.apply(a.floatValue(), b.floatValue())));
    }

    /*
     * Main executor. This will go through each instruction and execute the instruction using a switch statement
     */
    private static <T> T execute(WrappedClassNode classNode, MethodNode method, AbstractInsnNode now, List<JavaValue> stack, List<JavaValue> locals, Context context) {
        context.push(classNode.classNode.name, method.name, classNode.constantPoolSize);
        if (DEBUG) {
            System.out.println("Executing " + classNode.classNode.name + " " + method.name + method.desc);
        }
        forever:
        while (true) {
            try {
                if (DEBUG && (DEBUG_CLASSES.isEmpty() || DEBUG_CLASSES.contains(classNode.classNode.name)) && (DEBUG_METHODS_WITH_DESC.isEmpty() || DEBUG_METHODS_WITH_DESC.contains(method.name + method.desc))) {
                    System.out.println("\t Stack: " + stack);
                    System.out.println("\t Locals: " + locals);
                    System.out.println();
                    System.out.println(method.instructions.indexOf(now) + " " + Utils.prettyprint(now));
                }
                if (now == null) {
                    throw new FallingOffCodeException();
                }
                Throwable toThrow = null;
                switch (now.getOpcode()) {
                    case NOP:
                        break;
                    case ACONST_NULL:
                        stack.add(0, new JavaObject(null, "java/lang/Object"));
                        break;
                    case ICONST_M1:
                    case ICONST_0:
                    case ICONST_1:
                    case ICONST_2:
                    case ICONST_3:
                    case ICONST_4:
                    case ICONST_5:
                        stack.add(0, new JavaInteger(now.getOpcode() - 3));
                        break;
                    case LCONST_0:
                    case LCONST_1:
                        stack.add(0, new JavaLong(now.getOpcode() - 9));
                        stack.add(0, new JavaTop());
                        break;
                    case FCONST_0:
                    case FCONST_1:
                    case FCONST_2:
                        stack.add(0, new JavaFloat(now.getOpcode() - 11));
                        break;
                    case DCONST_0:
                    case DCONST_1:
                        stack.add(0, new JavaDouble(now.getOpcode() - 14));
                        stack.add(0, new JavaTop());
                        break;
                    case BIPUSH: {
                        IntInsnNode cast = (IntInsnNode) now;
                        stack.add(0, new JavaByte((byte) cast.operand));
                        break;
                    }
                    case SIPUSH: {
                        IntInsnNode cast = (IntInsnNode) now;
                        stack.add(0, new JavaShort((short) cast.operand));
                        break;
                    }
                    case LDC: {
                        LdcInsnNode cast = (LdcInsnNode) now;
                        Object load = cast.cst;
                        if (load instanceof Type) {
                            Type type = (Type) load;
                            load = new JavaClass(type.getInternalName().replace('/', '.'), context);
                        }
                        if (load instanceof Integer) {
                            stack.add(0, new JavaInteger((Integer) load));
                        } else if (load instanceof Float) {
                            stack.add(0, new JavaFloat((Float) load));
                        } else if (load instanceof Double) {
                            stack.add(0, new JavaDouble((Double) load));
                            stack.add(0, new JavaTop());
                        } else if (load instanceof Long) {
                            stack.add(0, new JavaLong((Long) load));
                            stack.add(0, new JavaTop());
                        } else if (load instanceof String) {
                            stack.add(0, new JavaObject(load, "java/lang/String"));
                        } else if (load instanceof JavaClass) {
                            stack.add(0, new JavaObject(load, "java/lang/Class"));
                        } else {
                            throw new ExecutionException("Unexpected ldc type " + (load == null ? "null" : load.getClass()));
                        }
                        break;
                    }
                    case ILOAD:
                    case FLOAD:
                    case ALOAD: {
                        VarInsnNode cast = (VarInsnNode) now;
                        stack.add(0, locals.get(cast.var).copy());
                        if (VERIFY) {
                            switch (now.getOpcode()) {
                                case ILOAD:
                                    if (!(stack.get(0) instanceof JavaInteger)) {
                                        throw new ExecutionException("Expected Integer");
                                    }
                                    break;
                                case FLOAD:
                                    if (!(stack.get(0) instanceof JavaFloat)) {
                                        throw new ExecutionException("Expected Float");
                                    }
                                    break;
                                case ALOAD:
                                    if (!(stack.get(0) instanceof JavaObject)) {
                                        throw new ExecutionException("Expected Object");
                                    }
                                    break;
                            }
                        }
                        break;
                    }
                    case LLOAD:
                    case DLOAD: {
                        VarInsnNode cast = (VarInsnNode) now;
                        stack.add(0, locals.get(cast.var).copy());
                        stack.add(0, new JavaTop());
                        if (VERIFY) {
                            switch (now.getOpcode()) {
                                case DLOAD:
                                    if (!(stack.get(1) instanceof JavaDouble)) {
                                        throw new ExecutionException("Expected Double");
                                    }
                                    break;
                                case LLOAD:
                                    if (!(stack.get(1) instanceof JavaLong)) {
                                        throw new ExecutionException("Expected Long");
                                    }
                                    break;
                            }
                        }
                        break;
                    }
                    case IALOAD:
                        executeArrayLoad(stack, int.class);
                        break;
                    case LALOAD:
                        executeArrayLoad(stack, long.class);
                        break;
                    case FALOAD:
                        executeArrayLoad(stack, float.class);
                        break;
                    case DALOAD:
                        executeArrayLoad(stack, double.class);
                        break;
                    case AALOAD:
                        executeArrayLoad(stack, Object.class);
                        break;
                    case BALOAD:
                        executeArrayLoad(stack, byte.class);
                        break;
                    case CALOAD:
                        executeArrayLoad(stack, char.class);
                        break;
                    case SALOAD:
                        executeArrayLoad(stack, short.class);
                        break;
                    case ISTORE:
                    case FSTORE:
                    case ASTORE: {
                        VarInsnNode cast = (VarInsnNode) now;
                        locals.set(cast.var, stack.remove(0).copy());
                        break;
                    }
                    case LSTORE:
                    case DSTORE: {
                        stack.remove(0);
                        VarInsnNode cast = (VarInsnNode) now;
                        locals.set(cast.var, stack.remove(0).copy());
                        break;
                    }
                    case IASTORE:
                    case LASTORE:
                    case FASTORE:
                    case DASTORE:
                    case BASTORE:
                    case CASTORE:
                    case SASTORE:
                    case AASTORE:
                        executeArrayStore(stack);
                        break;
                    case POP:
                        stack.remove(0);
                        break;
                    case POP2: {
                        stack.remove(0);
                        stack.remove(0);
                        break;
                    }
                    case DUP:
                        stack.add(0, stack.get(0));
                        break;
                    case DUP_X1: {
                        JavaValue obj = stack.get(0);
                        if (obj instanceof JavaDouble || obj instanceof JavaLong) {
                            throw new ExecutionException("Dup with double/long");
                        }
                        stack.add(2, stack.get(0));
                        break;
                    }
                    case DUP_X2: {
                        JavaValue obj = stack.get(0);
                        if (obj instanceof JavaDouble || obj instanceof JavaLong) {
                            throw new ExecutionException("Dup with double/long");
                        }
                        stack.add(3, obj);
                        break;
                    }
                    case DUP2: {
                        JavaValue obj = stack.get(0);
                        JavaValue obj1 = stack.get(1);
                        stack.add(2, obj);
                        stack.add(3, obj1);
                        break;
                    }
                    case DUP2_X1: {
                        JavaValue obj = stack.get(0);
                        JavaValue obj1 = stack.get(1);
                        stack.add(3, obj);
                        stack.add(4, obj1);
                        break;
                    }
                    case DUP2_X2: {
                        JavaValue obj = stack.get(0);
                        JavaValue obj1 = stack.get(1);
                        stack.add(4, obj);
                        stack.add(5, obj1);
                        break;
                    }
                    case SWAP: {
                        JavaValue a = stack.remove(0);
                        JavaValue b = stack.remove(0);
                        stack.add(0, a);
                        stack.add(0, b);
                        break;
                    }
                    case IADD:
                        doIntegerMath(stack, (x, y) -> x + y);
                        break;
                    case ISUB:
                        doIntegerMath(stack, (x, y) -> x - y);
                        break;
                    case IMUL:
                        doIntegerMath(stack, (x, y) -> x * y);
                        break;
                    case IDIV:
                        doIntegerMath(stack, (x, y) -> x / y);
                        break;
                    case IREM:
                        doIntegerMath(stack, (x, y) -> x % y);
                        break;
                    case ISHL:
                        doIntegerMath(stack, (x, y) -> x << y);
                        break;
                    case ISHR:
                        doIntegerMath(stack, (x, y) -> x >> y);
                        break;
                    case IUSHR:
                        doIntegerMath(stack, (x, y) -> x >>> y);
                        break;
                    case IAND:
                        doIntegerMath(stack, (x, y) -> x & y);
                        break;
                    case IOR:
                        doIntegerMath(stack, (x, y) -> x | y);
                        break;
                    case IXOR:
                        doIntegerMath(stack, (x, y) -> x ^ y);
                        break;
                    case LADD:
                        doLongMath(stack, (x, y) -> x + y);
                        break;
                    case LSUB:
                        doLongMath(stack, (x, y) -> x - y);
                        break;
                    case LMUL:
                        doLongMath(stack, (x, y) -> x * y);
                        break;
                    case LDIV:
                        doLongMath(stack, (x, y) -> x / y);
                        break;
                    case LREM:
                        doLongMath(stack, (x, y) -> x % y);
                        break;
                    case LSHL:
                        doLongShift(stack, (x, y) -> x << y);
                        break;
                    case LSHR:
                        doLongShift(stack, (x, y) -> x >> y);
                        break;
                    case LUSHR:
                        doLongShift(stack, (x, y) -> x >>> y);
                        break;
                    case LAND:
                        doLongMath(stack, (x, y) -> x & y);
                        break;
                    case LOR:
                        doLongMath(stack, (x, y) -> x | y);
                        break;
                    case LXOR:
                        doLongMath(stack, (x, y) -> x ^ y);
                        break;
                    case LCMP:
                        doLongMathReturnInteger(stack, (x, y) -> x.compareTo(y));
                        break;
                    case FADD:
                        doFloatMath(stack, (x, y) -> x + y);
                        break;
                    case FSUB:
                        doFloatMath(stack, (x, y) -> x - y);
                        break;
                    case FMUL:
                        doFloatMath(stack, (x, y) -> x * y);
                        break;
                    case FDIV:
                        doFloatMath(stack, (x, y) -> x / y);
                        break;
                    case FREM:
                        doFloatMath(stack, (x, y) -> x % y);
                        break;
                    case FCMPL:
                        doFloatMathReturnInteger(stack, (x, y) -> Float.isNaN(x) || Float.isNaN(y) ? -1 : x.compareTo(y));
                        break;
                    case FCMPG:
                        doFloatMathReturnInteger(stack, (x, y) -> Float.isNaN(x) || Float.isNaN(y) ? 1 : x.compareTo(y));
                        break;
                    case DADD:
                        doDoubleMath(stack, (x, y) -> x + y);
                        break;
                    case DSUB:
                        doDoubleMath(stack, (x, y) -> x - y);
                        break;
                    case DMUL:
                        doDoubleMath(stack, (x, y) -> x * y);
                        break;
                    case DDIV:
                        doDoubleMath(stack, (x, y) -> x / y);
                        break;
                    case DREM:
                        doDoubleMath(stack, (x, y) -> x % y);
                        break;
                    case DCMPL:
                        doDoubleMathReturnInteger(stack, (x, y) -> Double.isNaN(x) || Double.isNaN(y) ? -1 : x.compareTo(y));
                        break;
                    case DCMPG:
                        doDoubleMathReturnInteger(stack, (x, y) -> Double.isNaN(x) || Double.isNaN(y) ? 1 : x.compareTo(y));
                        break;
                    case INEG:
                        stack.set(0, new JavaInteger(-stack.get(0).intValue()));
                        break;
                    case LNEG:
                        stack.set(1, new JavaLong(-stack.get(1).longValue()));
                        break;
                    case FNEG:
                        stack.set(0, new JavaFloat(-stack.get(0).floatValue()));
                        break;
                    case DNEG:
                        stack.set(1, new JavaDouble(-stack.get(1).doubleValue()));
                        break;
                    case IINC: {
                        IincInsnNode cast = (IincInsnNode) now;
                        JavaInteger integer = (JavaInteger) locals.get(cast.var);
                        integer.increment(cast.incr);
                        break;
                    }
                    case I2L: {
                        stack.add(0, new JavaLong(stack.remove(0).intValue()));
                        stack.add(0, new JavaTop());
                        break;
                    }
                    case I2F: {
                        stack.add(0, new JavaFloat(stack.remove(0).intValue()));
                        break;
                    }
                    case I2D: {
                        stack.add(0, new JavaDouble(stack.remove(0).intValue()));
                        stack.add(0, new JavaTop());
                        break;
                    }
                    case L2I: {
                        JavaValue value = stack.remove(0);
                        if (VERIFY && !(value instanceof JavaTop)) {
                            throw new ExecutionException("Expected JavaTop");
                        }
                        stack.add(0, new JavaInteger((int) stack.remove(0).longValue()));
                        break;
                    }
                    case L2F: {
                        JavaValue value = stack.remove(0);
                        if (VERIFY && !(value instanceof JavaTop)) {
                            throw new ExecutionException("Expected JavaTop");
                        }
                        stack.add(0, new JavaFloat((float) stack.remove(0).longValue()));
                        break;
                    }
                    case L2D: {
                        stack.add(1, new JavaDouble(stack.remove(1).longValue()));
                        break;
                    }
                    case F2I: {
                        stack.add(0, new JavaInteger((int) stack.remove(0).floatValue()));
                        break;
                    }
                    case F2L: {
                        stack.add(0, new JavaLong((long) stack.remove(0).floatValue()));
                        stack.add(0, new JavaTop());
                        break;
                    }
                    case F2D: {
                        stack.add(0, new JavaDouble((double) stack.remove(0).floatValue()));
                        stack.add(0, new JavaTop());
                        break;
                    }
                    case D2I: {
                        JavaValue value = stack.remove(0);
                        if (VERIFY && !(value instanceof JavaTop)) {
                            throw new ExecutionException("Expected JavaTop");
                        }
                        stack.add(0, new JavaInteger((int) stack.remove(0).doubleValue()));
                        break;
                    }
                    case D2L: {
                        stack.add(1, new JavaLong((long) stack.remove(1).doubleValue()));
                        break;
                    }
                    case D2F: {
                        JavaValue value = stack.remove(0);
                        if (VERIFY && !(value instanceof JavaTop)) {
                            throw new ExecutionException("Expected JavaTop");
                        }
                        stack.add(0, new JavaFloat((float) stack.remove(0).doubleValue()));
                        break;
                    }
                    case I2B: {
                        stack.add(0, new JavaByte((byte) stack.remove(0).intValue()));
                        break;
                    }
                    case I2C: {
                        stack.add(0, new JavaCharacter((char) stack.remove(0).intValue()));
                        break;
                    }
                    case I2S: {
                        stack.add(0, new JavaShort((short) stack.remove(0).intValue()));
                        break;
                    }
                    case IFEQ: {
                        JumpInsnNode cast = (JumpInsnNode) now;
                        JavaValue o = stack.remove(0);
                        if (o.intValue() == 0) {
                            now = cast.label;
                        }
                        break;
                    }
                    case IFNE: {
                        JumpInsnNode cast = (JumpInsnNode) now;
                        JavaValue o = stack.remove(0);
                        if (o.intValue() != 0) {
                            now = cast.label;
                        }
                        break;
                    }
                    case IFLT: {
                        JumpInsnNode cast = (JumpInsnNode) now;
                        JavaValue o = stack.remove(0);
                        if (o.intValue() < 0) {
                            now = cast.label;
                        }
                        break;
                    }
                    case IFGE: {
                        JumpInsnNode cast = (JumpInsnNode) now;
                        JavaValue o = stack.remove(0);
                        if (o.intValue() >= 0) {
                            now = cast.label;
                        }
                        break;
                    }
                    case IFGT: {
                        JumpInsnNode cast = (JumpInsnNode) now;
                        JavaValue o = stack.remove(0);
                        if (o.intValue() > 0) {
                            now = cast.label;
                        }
                        break;
                    }
                    case IFLE: {
                        JumpInsnNode cast = (JumpInsnNode) now;
                        JavaValue o = stack.remove(0);
                        if (o.intValue() <= 0) {
                            now = cast.label;
                        }
                        break;
                    }
                    case IF_ICMPEQ: {
                        JumpInsnNode cast = (JumpInsnNode) now;
                        JavaValue o = stack.remove(0);
                        JavaValue o1 = stack.remove(0);
                        if (o.intValue() == o1.intValue()) {
                            now = cast.label;
                        }
                        break;
                    }
                    case IF_ICMPNE: {
                        JumpInsnNode cast = (JumpInsnNode) now;
                        JavaValue o = stack.remove(0);
                        JavaValue o1 = stack.remove(0);
                        if (o.intValue() != o1.intValue()) {
                            now = cast.label;
                        }
                        break;
                    }
                    case IF_ICMPLT: {
                        JumpInsnNode cast = (JumpInsnNode) now;
                        JavaValue o = stack.remove(0);
                        JavaValue o1 = stack.remove(0);
                        if (o1.intValue() < o.intValue()) {
                            now = cast.label;
                        }
                        break;
                    }
                    case IF_ICMPGE: {
                        JumpInsnNode cast = (JumpInsnNode) now;
                        JavaValue o = stack.remove(0);
                        JavaValue o1 = stack.remove(0);
                        if (o1.intValue() >= o.intValue()) {
                            now = cast.label;
                        }
                        break;
                    }
                    case IF_ICMPGT: {
                        JumpInsnNode cast = (JumpInsnNode) now;
                        JavaValue o = stack.remove(0);
                        JavaValue o1 = stack.remove(0);
                        if (o1.intValue() > o.intValue()) {
                            now = cast.label;
                        }
                        break;
                    }
                    case IF_ICMPLE: {
                        JumpInsnNode cast = (JumpInsnNode) now;
                        JavaValue o = stack.remove(0);
                        JavaValue o1 = stack.remove(0);
                        if (o1.intValue() <= o.intValue()) {
                            now = cast.label;
                        }
                        break;
                    }
                    case IF_ACMPNE: {
                        JumpInsnNode cast = (JumpInsnNode) now;
                        JavaValue o = stack.remove(0);
                        JavaValue o1 = stack.remove(0);
                        if (context.provider.canCheckEquality(o, o1, context)) {
                            boolean eq = context.provider.checkEquality(o, o1, context);
                            if (!eq) {
                                now = cast.label;
                            }
                        } else {
                            throw new NoSuchMethodHandlerException("Could not find comparison for " + o.type() + " " + o1.type());
                        }
                        break;
                    }
                    case IF_ACMPEQ: {
                        JumpInsnNode cast = (JumpInsnNode) now;
                        JavaValue o = stack.remove(0);
                        JavaValue o1 = stack.remove(0);
                        if (context.provider.canCheckEquality(o, o1, context)) {
                            boolean eq = context.provider.checkEquality(o, o1, context);
                            if (eq) {
                                now = cast.label;
                            }
                        } else {
                            throw new NoSuchMethodHandlerException("Could not find comparison for " + o.type() + " " + o1.type());
                        }
                        break;
                    }
                    case GOTO: {
                        JumpInsnNode cast = (JumpInsnNode) now;
                        now = cast.label;
                        break;
                    }
                    case JSR: {
                        JumpInsnNode cast = (JumpInsnNode) now;
                        stack.add(0, new JavaAddress(now));
                        now = cast.label;
                        break;
                    }
                    case RET: {
                        VarInsnNode cast = (VarInsnNode) now;
                        JavaValue value = locals.get(cast.var);
                        if (!(value instanceof JavaAddress)) {
                            throw new ExecutionException("Expected address on stack");
                        }
                        now = (AbstractInsnNode) ((JavaAddress) value).value();
                        break;
                    }
                    case TABLESWITCH: {
                        int x = stack.remove(0).intValue();
                        TableSwitchInsnNode cast = (TableSwitchInsnNode) now;
                        int offset = cast.min;
                        if (x < cast.labels.size() + offset && x - offset >= 0) {
                            now = cast.labels.get(x - offset);
                        } else {
                            now = cast.dflt;
                        }
                        break;
                    }
                    case LOOKUPSWITCH: {
                        Integer x = stack.remove(0).intValue();
                        LookupSwitchInsnNode cast = (LookupSwitchInsnNode) now;
                        if (cast.keys.indexOf(x) != -1) {
                            now = cast.labels.get(cast.keys.indexOf(x));
                        } else {
                            now = cast.dflt;
                        }
                        break;
                    }
                    case IRETURN: {
                        context.pop();
                        return (T) (Integer) stack.remove(0).intValue();
                    }
                    case LRETURN: {
                        context.pop();
                        stack.remove(0);
                        return (T) (Long) stack.remove(0).longValue();
                    }
                    case FRETURN: {
                        context.pop();
                        return (T) (Float) stack.remove(0).floatValue();
                    }
                    case DRETURN: {
                        context.pop();
                        stack.remove(0);
                        return (T) (Double) stack.remove(0).doubleValue();
                    }
                    case ARETURN: {
                        context.pop();
                        return (T) stack.remove(0).value();
                    }
                    case RETURN: {
                        context.pop();
                        return (T) Optional.absent();
                    }
                    case GETSTATIC: {
                        FieldInsnNode cast = (FieldInsnNode) now;
                        Type type = Type.getType(cast.desc);
                        Class<?> clazz = PrimitiveUtils.getPrimitiveByName(type.getClassName());
                        Object provided = context.provider.getField(cast.owner, cast.name, cast.desc, null, context);

                        switch (type.getReturnType().getSort()) {
                            case Type.BOOLEAN:
                                stack.add(0, new JavaBoolean((Boolean) provided));
                                break;
                            case Type.CHAR:
                                stack.add(0, new JavaCharacter((Character) provided));
                                break;
                            case Type.BYTE:
                                stack.add(0, new JavaByte((Byte) provided));
                                break;
                            case Type.SHORT:
                                stack.add(0, new JavaShort((Short) provided));
                                break;
                            case Type.INT:
                                stack.add(0, new JavaInteger((Integer) provided));
                                break;
                            case Type.FLOAT:
                                stack.add(0, new JavaFloat((Float) provided));
                                break;
                            case Type.LONG:
                                stack.add(0, new JavaLong((Long) provided));
                                stack.add(0, new JavaTop());
                                break;
                            case Type.DOUBLE:
                                stack.add(0, new JavaDouble((Double) provided));
                                stack.add(0, new JavaTop());
                                break;
                            case Type.ARRAY:
                            case Type.OBJECT:
                                stack.add(0, new JavaObject(provided, "java/lang/Object"));
                                break;
                        }
                        break;
                    }
                    case PUTSTATIC: {
                        JavaValue obj = stack.remove(0);
                        FieldInsnNode cast = (FieldInsnNode) now;
                        context.provider.setField(cast.owner, cast.name, cast.desc, null, convert(value(obj), cast.desc), context);
                        break;
                    }
                    case GETFIELD: {
                        JavaValue obj = stack.remove(0);
                        FieldInsnNode cast = (FieldInsnNode) now;
                        Type type = Type.getType(cast.desc);
                        Class<?> clazz = PrimitiveUtils.getPrimitiveByName(type.getClassName());
                        Object provided = context.provider.getField(cast.owner, cast.name, cast.desc, obj, context);
                        switch (type.getReturnType().getSort()) {
                            case Type.BOOLEAN:
                                stack.add(0, new JavaBoolean((Boolean) provided));
                                break;
                            case Type.CHAR:
                                stack.add(0, new JavaCharacter((Character) provided));
                                break;
                            case Type.BYTE:
                                stack.add(0, new JavaByte((Byte) provided));
                                break;
                            case Type.SHORT:
                                stack.add(0, new JavaShort((Short) provided));
                                break;
                            case Type.INT:
                                stack.add(0, new JavaInteger((Integer) provided));
                                break;
                            case Type.FLOAT:
                                stack.add(0, new JavaFloat((Float) provided));
                                break;
                            case Type.LONG:
                                stack.add(0, new JavaLong((Long) provided));
                                stack.add(0, new JavaTop());
                                break;
                            case Type.DOUBLE:
                                stack.add(0, new JavaDouble((Double) provided));
                                stack.add(0, new JavaTop());
                                break;
                            case Type.ARRAY:
                            case Type.OBJECT:
                                stack.add(0, new JavaObject(provided, "java/lang/Object"));
                                break;
                        }
                        break;
                    }
                    case PUTFIELD: {
                        JavaValue obj = stack.remove(0);
                        JavaValue instance = stack.remove(0);
                        FieldInsnNode cast = (FieldInsnNode) now;
                        context.provider.setField(cast.owner, cast.name, cast.desc, instance, convert(value(obj), cast.desc), context);
                        break;
                    }
                    case INVOKEVIRTUAL: {
                        MethodInsnNode cast = (MethodInsnNode) now;
                        Type type = Type.getReturnType(cast.desc);
                        List<JavaValue> args = new ArrayList<>();
                        for (Type t1 : Type.getArgumentTypes(cast.desc)) {
                            if (t1.getSort() == Type.LONG || t1.getSort() == Type.DOUBLE) {
                                if (!(stack.get(0) instanceof JavaTop)) {
                                    throw new ExecutionException("Expected JavaTop");
                                }
                                stack.remove(0);
                            }
                            args.add(0, stack.remove(0).copy());
                        }
                        args.add(stack.remove(0));
                        String owner = cast.owner;
                        while(true) {
                            try {
                                if (context.provider.canInvokeMethod(owner, cast.name, cast.desc, args.get(args.size() - 1), args.subList(0, args.size() - 1), context)) {
                                    Object provided = context.provider.invokeMethod(owner, cast.name, cast.desc, args.get(args.size() - 1), args.subList(0, args.size() - 1), context);
                                    switch (type.getReturnType().getSort()) {
                                        case Type.BOOLEAN:
                                            stack.add(0, new JavaBoolean((Boolean) provided));
                                            break;
                                        case Type.CHAR:
                                            stack.add(0, new JavaCharacter((Character) provided));
                                            break;
                                        case Type.BYTE:
                                            stack.add(0, new JavaByte((Byte) provided));
                                            break;
                                        case Type.SHORT:
                                            stack.add(0, new JavaShort((Short) provided));
                                            break;
                                        case Type.INT:
                                            stack.add(0, new JavaInteger((Integer) provided));
                                            break;
                                        case Type.FLOAT:
                                            stack.add(0, new JavaFloat((Float) provided));
                                            break;
                                        case Type.LONG:
                                            stack.add(0, new JavaLong((Long) provided));
                                            stack.add(0, new JavaTop());
                                            break;
                                        case Type.DOUBLE:
                                            stack.add(0, new JavaDouble((Double) provided));
                                            stack.add(0, new JavaTop());
                                            break;
                                        case Type.ARRAY:
                                        case Type.OBJECT:
                                            stack.add(0, new JavaObject(provided, "java/lang/Object"));
                                            break;
                                    }
                                } else {
                                    throw new NoSuchMethodHandlerException("Could not find invoker for " + cast.owner + " " + cast.name + cast.desc);
                                }
                                break;
                            } catch (NoSuchMethodHandlerException | IllegalArgumentException t) {
                                WrappedClassNode ownerWrappedClass = context.dictionary.get(owner);
                                if (ownerWrappedClass != null) {
                                    ClassNode ownerClass = ownerWrappedClass.classNode;
                                    if (ownerClass.superName != null) {
                                        owner = ownerClass.superName;
                                        continue;
                                    }
                                }
                                throw t;
                            }
                        }
                        break;
                    }
                    case INVOKESPECIAL: {
                        MethodInsnNode cast = (MethodInsnNode) now;
                        Type type = Type.getReturnType(cast.desc);
                        List<JavaValue> args = new ArrayList<>();
                        for (Type t1 : Type.getArgumentTypes(cast.desc)) {
                            if (t1.getSort() == Type.LONG || t1.getSort() == Type.DOUBLE) {
                                if (!(stack.get(0) instanceof JavaTop)) {
                                    throw new ExecutionException("Expected JavaTop");
                                }
                                stack.remove(0);
                            }
                            args.add(0, stack.remove(0).copy());
                        }
                        args.add(stack.remove(0));
                        String owner = cast.owner;
                        while(true) {
                            try {
                                if (context.provider.canInvokeMethod(owner, cast.name, cast.desc, args.get(args.size() - 1), args.subList(0, args.size() - 1), context)) {
                                    Object provided = context.provider.invokeMethod(owner, cast.name, cast.desc, args.get(args.size() - 1), args.subList(0, args.size() - 1), context);
                                    switch (type.getReturnType().getSort()) {
                                        case Type.BOOLEAN:
                                            stack.add(0, new JavaBoolean((Boolean) provided));
                                            break;
                                        case Type.CHAR:
                                            stack.add(0, new JavaCharacter((Character) provided));
                                            break;
                                        case Type.BYTE:
                                            stack.add(0, new JavaByte((Byte) provided));
                                            break;
                                        case Type.SHORT:
                                            stack.add(0, new JavaShort((Short) provided));
                                            break;
                                        case Type.INT:
                                            stack.add(0, new JavaInteger((Integer) provided));
                                            break;
                                        case Type.FLOAT:
                                            stack.add(0, new JavaFloat((Float) provided));
                                            break;
                                        case Type.LONG:
                                            stack.add(0, new JavaLong((Long) provided));
                                            stack.add(0, new JavaTop());
                                            break;
                                        case Type.DOUBLE:
                                            stack.add(0, new JavaDouble((Double) provided));
                                            stack.add(0, new JavaTop());
                                            break;
                                        case Type.ARRAY:
                                        case Type.OBJECT:
                                            stack.add(0, new JavaObject(provided, "java/lang/Object"));
                                            break;
                                    }
                                } else {
                                    throw new NoSuchMethodHandlerException("Could not find invoker for " + cast.owner + " " + cast.name + cast.desc);
                                }
                                break;
                            } catch (NoSuchMethodHandlerException | IllegalArgumentException t) {
                                WrappedClassNode ownerWrappedClass = context.dictionary.get(owner);
                                if (ownerWrappedClass != null) {
                                    ClassNode ownerClass = ownerWrappedClass.classNode;
                                    if (ownerClass.superName != null) {
                                        owner = ownerClass.superName;
                                        continue;
                                    }
                                }
                                throw t;
                            }
                        }
                        break;
                    }
                    case INVOKESTATIC: {
                        MethodInsnNode cast = (MethodInsnNode) now;
                        Type type = Type.getReturnType(cast.desc);
                        List<JavaValue> args = new ArrayList<>();
                        List<Type> l = new ArrayList<>(Arrays.asList(Type.getArgumentTypes(cast.desc)));
                        Collections.reverse(l);
                        for (Type t1 : l) {
                            if (t1.getSort() == Type.LONG || t1.getSort() == Type.DOUBLE) {
                                if (!(stack.get(0) instanceof JavaTop)) {
                                    throw new ExecutionException("Expected JavaTop while invoking " + cast.owner + " " + cast.name + " " + cast.desc + ", but got " + stack.get(0).getClass().getSimpleName());
                                }
                                stack.remove(0);
                            }
                            args.add(0, stack.remove(0).copy());
                        }
                        if (context.provider.canInvokeMethod(cast.owner, cast.name, cast.desc, null, args, context)) {
                            Object provided = context.provider.invokeMethod(cast.owner, cast.name, cast.desc, null, args, context);
                            switch (type.getReturnType().getSort()) {
                                case Type.BOOLEAN:
                                    stack.add(0, new JavaBoolean((Boolean) provided));
                                    break;
                                case Type.CHAR:
                                    stack.add(0, new JavaCharacter((Character) provided));
                                    break;
                                case Type.BYTE:
                                    stack.add(0, new JavaByte((Byte) provided));
                                    break;
                                case Type.SHORT:
                                    stack.add(0, new JavaShort((Short) provided));
                                    break;
                                case Type.INT:
                                    stack.add(0, new JavaInteger((Integer) provided));
                                    break;
                                case Type.FLOAT:
                                    stack.add(0, new JavaFloat((Float) provided));
                                    break;
                                case Type.LONG:
                                    stack.add(0, new JavaLong((Long) provided));
                                    stack.add(0, new JavaTop());
                                    break;
                                case Type.DOUBLE:
                                    stack.add(0, new JavaDouble((Double) provided));
                                    stack.add(0, new JavaTop());
                                    break;
                                case Type.ARRAY:
                                case Type.OBJECT:
                                    stack.add(0, new JavaObject(provided, "java/lang/Object"));
                                    break;
                            }
                        } else {
                            throw new NoSuchMethodHandlerException("Could not find invoker for " + cast.owner + " " + cast.name + cast.desc);
                        }
                        break;
                    }
                    case INVOKEINTERFACE: {
                        MethodInsnNode cast = (MethodInsnNode) now;
                        Type type = Type.getReturnType(cast.desc);
                        List<JavaValue> args = new ArrayList<>();
                        for (Type t1 : Type.getArgumentTypes(cast.desc)) {
                            if (t1.getSort() == Type.LONG || t1.getSort() == Type.DOUBLE) {
                                if (!(stack.get(0) instanceof JavaTop)) {
                                    throw new ExecutionException("Expected JavaTop");
                                }
                                stack.remove(0);
                            }
                            args.add(0, stack.remove(0).copy());
                        }
                        args.add(stack.remove(0));
                        if (context.provider.canInvokeMethod(cast.owner, cast.name, cast.desc, args.get(args.size() - 1), args.subList(0, args.size() - 1), context)) {
                            Object provided = context.provider.invokeMethod(cast.owner, cast.name, cast.desc, args.get(args.size() - 1), args.subList(0, args.size() - 1), context);
                            switch (type.getReturnType().getSort()) {
                                case Type.BOOLEAN:
                                    stack.add(0, new JavaBoolean((Boolean) provided));
                                    break;
                                case Type.CHAR:
                                    stack.add(0, new JavaCharacter((Character) provided));
                                    break;
                                case Type.BYTE:
                                    stack.add(0, new JavaByte((Byte) provided));
                                    break;
                                case Type.SHORT:
                                    stack.add(0, new JavaShort((Short) provided));
                                    break;
                                case Type.INT:
                                    stack.add(0, new JavaInteger((Integer) provided));
                                    break;
                                case Type.FLOAT:
                                    stack.add(0, new JavaFloat((Float) provided));
                                    break;
                                case Type.LONG:
                                    stack.add(0, new JavaLong((Long) provided));
                                    stack.add(0, new JavaTop());
                                    break;
                                case Type.DOUBLE:
                                    stack.add(0, new JavaDouble((Double) provided));
                                    stack.add(0, new JavaTop());
                                    break;
                                case Type.ARRAY:
                                case Type.OBJECT:
                                    stack.add(0, new JavaObject(provided, "java/lang/Object"));
                                    break;
                            }
                        } else {
                            throw new NoSuchMethodHandlerException("Could not find invoker for " + cast.owner + " " + cast.name + cast.desc);
                        }
                        break;
                    }
                    case INVOKEDYNAMIC: {
                        throw new ExecutionException(new UnsupportedOperationException());
                    }
                    case NEW: {
                        TypeInsnNode cast = (TypeInsnNode) now;
                        stack.add(0, new JavaObject(cast.desc));
                        break;
                    }
                    case NEWARRAY: {
                        int len = stack.remove(0).intValue();
                        IntInsnNode cast = (IntInsnNode) now;
                        Object add = null;
                        switch (cast.operand) {
                            case T_BOOLEAN:
                                add = new boolean[len];
                                break;
                            case T_CHAR:
                                add = new char[len];
                                break;
                            case T_FLOAT:
                                add = new float[len];
                                break;
                            case T_DOUBLE:
                                add = new double[len];
                                break;
                            case T_BYTE:
                                add = new byte[len];
                                break;
                            case T_SHORT:
                                add = new short[len];
                                break;
                            case T_INT:
                                add = new int[len];
                                break;
                            case T_LONG:
                                add = new long[len];
                                break;
                            default:
                                throw new ExecutionException("Unknown newarray type " + cast.operand);
                        }
                        stack.add(0, new JavaObject(add, "java/lang/Object"));
                        break;
                    }
                    case ANEWARRAY: {
                        int len = stack.remove(0).intValue();
                        stack.add(0, new JavaObject(new Object[len], "java/lang/Object"));
                        break;
                    }
                    case ARRAYLENGTH: {
                        JavaValue obj = stack.remove(0);
                        if (VERIFY && obj.value() == null) {
                            throw new ExecutionException("Array is null");
                        }
                        int len = Array.getLength(obj.value());
                        stack.add(0, new JavaInteger(len));
                        break;
                    }
                    case ATHROW: {
                        Object throwable = stack.remove(0).value();
                        if (throwable instanceof Throwable) {
                            toThrow = (Throwable) throwable;
                            break;
                        } else if (throwable == null) {
                            NullPointerException exception = new NullPointerException();
                            exception.setStackTrace(context.getStackTrace());
                            toThrow = exception;
                            break;
                        } else {
                            throw new ExecutionException("Expected a throwable on stack");
                        }
                    }
                    case CHECKCAST: {
                        TypeInsnNode cast = (TypeInsnNode) now;
                        JavaValue obj = stack.get(0);
                        if (obj.value() != null) {
                            if (context.provider.canCheckcast(obj, Type.getType(cast.desc), context)) {
                                if (!context.provider.checkcast(obj, Type.getType(cast.desc), context)) {
                                    throw new ClassCastException(cast.desc);
                                }
                            } else {
                                throw new NoSuchComparisonHandlerException("No comparator found for " + cast.desc);
                            }
                        }
                        break;
                    }
                    case INSTANCEOF: {
                        TypeInsnNode cast = (TypeInsnNode) now;
                        JavaValue obj = stack.remove(0);
                        if (context.provider.canCheckInstanceOf(obj, Type.getType(cast.desc), context)) {
                            boolean is = context.provider.instanceOf(obj, Type.getType(cast.desc), context);
                            stack.add(0, new JavaInteger(is ? 1 : 0));
                        } else {
                            throw new NoSuchComparisonHandlerException("No comparator found for " + cast.desc);
                        }
                        break;
                    }
                    case MONITORENTER: {
                        Monitor.enter(stack.remove(0));
                        break;
                    }
                    case MONITOREXIT: {
                        Monitor.exit(stack.remove(0));
                        break;
                    }
                    case MULTIANEWARRAY: {
                        MultiANewArrayInsnNode cast = (MultiANewArrayInsnNode) now;
                        List<Integer> sizes = new ArrayList<>();
                        for (int i = 0; i < cast.dims; i++) {
                            sizes.add(0, stack.remove(0).intValue());
                        }
                        Type type = Type.getType(cast.desc);
                        Class<?> clazz = PrimitiveUtils.getPrimitiveByName(type.getClassName());
                        Class<?> create = clazz == null ? Object.class : clazz;
                        Object root = Array.newInstance(create, sizes.get(0));
                        List<Object> currentArray = new ArrayList<>();
                        currentArray.add(root);

                        for (int i = 0; i < sizes.size() - 1; i++) {
                            List<Object> temp = new ArrayList<>(currentArray);
                            currentArray.clear();
                            int size = sizes.get(i);
                            for (int s = 0; s < size; s++) {
                                for (Object o : temp) {
                                    Object created = Array.newInstance(create, sizes.get(i + 1));
                                    currentArray.add(created);
                                    Array.set(o, s, created);
                                }
                            }
                        }
                        stack.add(0, new JavaObject(root, "java/lang/Object"));
                        break;
                    }
                    case IFNULL: {
                        JumpInsnNode cast = (JumpInsnNode) now;
                        JavaValue obj = stack.remove(0);
                        if (obj.value() == null) {
                            now = cast.label;
                        }
                        break;
                    }
                    case IFNONNULL: {
                        JumpInsnNode cast = (JumpInsnNode) now;
                        JavaValue obj = stack.remove(0);
                        if (obj.value() != null) {
                            now = cast.label;
                        }
                        break;
                    }
                    case -1: {
                        break;
                    }
                    default: {
                        throw new ExecutionException("Unknown opcode " + now.getClass().getSimpleName() + " " + now.getOpcode() + " ");
                    }
                }

                if (toThrow != null) {
                    if (DEBUG_PRINT_EXCEPTIONS) {
                        toThrow.printStackTrace(System.out);
                    }
                    if (method.tryCatchBlocks != null) {
                        for (TryCatchBlockNode tcbn : method.tryCatchBlocks) {
                            if (method.instructions.indexOf(tcbn.start) <= method.instructions.indexOf(now) && method.instructions.indexOf(now) < method.instructions.indexOf(tcbn.end)) {
                                if (tcbn.type == null || tcbn.type.equals("java/lang/Throwable")) {
                                    stack.clear();
                                    stack.add(new JavaObject(toThrow, "java/lang/Object"));
                                    now = tcbn.handler;
                                    continue forever;
                                } else {
                                    WrappedClassNode wr = context.dictionary.get(Type.getType(toThrow.getClass()).getInternalName());
                                    if (wr.classNode != null) {
                                        ClassNode cn = wr.classNode;
                                        boolean ok = false;
                                        while (cn != null) {
                                            if (cn.name.equals(tcbn.type)) {
                                                ok = true;
                                                break;
                                            }
                                            if (cn.superName == null) {
                                                break;
                                            }
                                            wr = context.dictionary.get(cn.superName);
                                            if (wr != null) {
                                                cn = wr.classNode;
                                            }
                                        }
                                        if (ok) {
                                            stack.clear();
                                            stack.add(new JavaObject(toThrow, "java/lang/Object"));
                                            now = tcbn.handler;
                                            continue forever;
                                        }
                                    }
                                }
                            }
                        }
                    }
                    throw new ExecutionException(toThrow);
                }
                now = now.getNext();
            } catch (ExecutionException e) {
                if (e.clazz.isEmpty()) {
                    e.clazz = classNode.classNode.name;
                    e.method = method.name + method.desc;
                }
                throw e;
            } catch (Throwable t) {
                if (DEBUG_PRINT_EXCEPTIONS) {
                    t.printStackTrace(System.out);
                }
                if (method.tryCatchBlocks != null) {
                    for (TryCatchBlockNode tcbn : method.tryCatchBlocks) {
                        if (method.instructions.indexOf(tcbn.start) <= method.instructions.indexOf(now) && method.instructions.indexOf(now) < method.instructions.indexOf(tcbn.end)) {
                            if (tcbn.type == null || tcbn.type.equals("java/lang/Throwable")) {
                                stack.clear();
                                stack.add(new JavaObject(t, "java/lang/Object"));
                                now = tcbn.handler;
                                continue forever;
                            } else {
                                WrappedClassNode wr = context.dictionary.get(Type.getType(t.getClass()).getInternalName());
                                if (wr.classNode != null) {
                                    ClassNode cn = wr.classNode;
                                    boolean ok = false;
                                    while (cn != null) {
                                        if (cn.name.equals(tcbn.type)) {
                                            ok = true;
                                            break;
                                        }
                                        if (cn.superName == null) {
                                            break;
                                        }
                                        wr = context.dictionary.get(cn.superName);
                                        if (wr != null) {
                                            cn = wr.classNode;
                                        }
                                    }
                                    if (ok) {
                                        stack.clear();
                                        stack.add(new JavaObject(t, "java/lang/Object"));
                                        now = tcbn.handler;
                                        continue forever;
                                    }
                                }
                            }
                        }
                    }
                }
                Utils.sneakyThrow(t);
                return null;
            }
        }
    }
}
