package com.flowglass.engine

class ComponentRegistry {
    private val components = linkedMapOf<String, ComponentMeta>()

    @Synchronized
    fun register(meta: ComponentMeta) {
        require(meta.typeId.isNotBlank()) { "typeId 不能为空" }
        require(meta.name.isNotBlank()) { "组件名称不能为空" }
        require(meta.category.isNotBlank()) { "组件分类不能为空" }
        require(meta.params.map { it.key }.distinct().size == meta.params.size) { "参数 key 重复" }
        require(meta.typeId !in components) { "组件 typeId 重复：${meta.typeId}" }
        components[meta.typeId] = meta
    }

    @Synchronized
    fun find(typeId: String): ComponentMeta? = components[typeId]

    @Synchronized
    fun all(): List<ComponentMeta> = components.values.toList()

    @Synchronized
    fun byCategory(category: String): List<ComponentMeta> = components.values.filter { it.category == category }

    @Synchronized
    fun availableFor(tier: Tier): List<ComponentMeta> = components.values.filter { it.minTier.ordinal <= tier.ordinal }

    @Synchronized
    fun byKind(kind: ComponentKind): List<ComponentMeta> = components.values.filter { it.kind == kind }
}
