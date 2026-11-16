package com.flowglass.engine

/**
 * Narrow bridge between a sandboxed script action and the interpreter's variable scopes.
 *
 * Scripts use `get(name)` / `set(name, value)`; the host translates writes into the same
 * rule/global persistence path as the SET_VAR block, so a script cannot bypass validation.
 */
interface ScriptVariableAccess {
    fun read(name: String): Any?
    fun write(name: String, value: Any?)
}
