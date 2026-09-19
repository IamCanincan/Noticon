package com.iamcanincan.noticon.util

import java.lang.reflect.Field
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/**
 * 反射取成员的薄封装。
 *
 * 只用标准 Java 反射，不碰框架自带的 Helper —— 这样同一份代码在不同实现下都能跑。
 * 统一约定是「取不到就放弃」：所有查找失败一律返回 null，
 * 因为代码跑在 SystemUI 进程里，一次反射失手不该把系统界面带崩。
 */
object MemberLookup {

    fun findClass(name: String, classLoader: ClassLoader): Class<*>? =
        runCatching { classLoader.loadClass(name) }.getOrNull()

    fun readField(target: Any, name: String): Any? = try {
        val f: Field = target.javaClass.getDeclaredField(name)
        f.isAccessible = true
        f.get(target)
    } catch (_: Throwable) {
        null
    }

    /**
     * 按字段类型而不是名字取值。
     * SystemUI 内部类改名的频率远高于改类型，靠类型找更耐版本变化。
     */
    fun readFieldByType(target: Any, type: Class<*>): Any? {
        return try {
            for (f in target.javaClass.declaredFields) {
                f.isAccessible = true
                if (f.type == type) return f.get(target)
            }
            null
        } catch (_: Throwable) {
            null
        }
    }

    fun writeField(target: Any, name: String, value: Any?) {
        runCatching {
            val f: Field = target.javaClass.getDeclaredField(name)
            f.isAccessible = true
            f.set(target, value)
        }
    }

    fun invoke(
        target: Any,
        name: String,
        args: Array<out Any?> = emptyArray(),
        vararg paramTypes: Class<*>
    ): Any? = try {
        val m = declaredMethod(target.javaClass, name, *paramTypes)
        m.isAccessible = true
        m.invoke(target, *args)
    } catch (e: Throwable) {
        // 目标方法自己抛的异常才有诊断价值，剥掉反射包装再抛
        if (e is InvocationTargetException) e.targetException else e
    }

    fun invokeStatic(
        clazz: Class<*>,
        name: String,
        args: Array<out Any?> = emptyArray(),
        vararg paramTypes: Class<*>
    ): Any? = try {
        val m: Method = clazz.getDeclaredMethod(name, *paramTypes)
        m.isAccessible = true
        m.invoke(null, *args)
    } catch (_: Throwable) {
        null
    }

    /** 沿父类链向上找声明的方法 */
    fun declaredMethod(clazz: Class<*>, name: String, vararg paramTypes: Class<*>): Method {
        var c: Class<*>? = clazz
        while (c != null) {
            try {
                return if (paramTypes.isEmpty()) c.getDeclaredMethod(name)
                else c.getDeclaredMethod(name, *paramTypes)
            } catch (_: NoSuchMethodException) {
                c = c.superclass
            }
        }
        throw NoSuchMethodException(name)
    }

    /**
     * 只要签名里出现过给定类型就命中，不要求顺序和个数完全一致。
     * SystemUI 给同一方法增删参数是家常便饭，精确匹配反而容易挂不上。
     */
    fun methodWithParams(clazz: Class<*>, name: String, vararg params: Class<*>): Method? {
        var c: Class<*>? = clazz
        while (c != null) {
            for (m in c.declaredMethods) {
                if (m.name != name) continue
                m.isAccessible = true
                if (params.isEmpty()) return m
                val types = m.parameterTypes.toList()
                if (types.isEmpty()) continue
                if (params.all { types.contains(it) }) return m
            }
            c = c.superclass
        }
        return null
    }
}
