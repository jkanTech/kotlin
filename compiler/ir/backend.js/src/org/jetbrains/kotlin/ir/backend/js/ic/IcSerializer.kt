/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.backend.js.ic

import org.jetbrains.kotlin.backend.common.serialization.DeclarationTable
import org.jetbrains.kotlin.backend.common.serialization.signature.IdSignatureSerializer
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.backend.js.JsMapping
import org.jetbrains.kotlin.ir.backend.js.SerializedMappings
import org.jetbrains.kotlin.ir.backend.js.lower.serialization.ir.JsGlobalDeclarationTable
import org.jetbrains.kotlin.ir.backend.js.lower.serialization.ir.JsIrFileSerializer
import org.jetbrains.kotlin.ir.backend.js.lower.serialization.ir.JsIrLinker
import org.jetbrains.kotlin.ir.backend.js.lower.serialization.ir.JsManglerIr
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.declarations.persistent.PersistentIrBodyBase
import org.jetbrains.kotlin.ir.declarations.persistent.PersistentIrDeclarationBase
import org.jetbrains.kotlin.ir.declarations.persistent.PersistentIrFactory
import org.jetbrains.kotlin.ir.descriptors.IrBuiltIns
import org.jetbrains.kotlin.ir.expressions.IrExpressionBody
import org.jetbrains.kotlin.ir.serialization.SerializedCarriers
import org.jetbrains.kotlin.ir.serialization.serializeCarriers
import org.jetbrains.kotlin.ir.symbols.IrSymbol
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.ir.visitors.IrElementVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.ir.visitors.acceptVoid
import org.jetbrains.kotlin.library.SerializedIrFile
import org.jetbrains.kotlin.library.impl.IrMemoryArrayWriter
import org.jetbrains.kotlin.library.impl.IrMemoryLongArrayWriter

class IcSerializer(
    irBuiltIns: IrBuiltIns,
    val mappings: JsMapping,
    val irFactory: PersistentIrFactory,
    val linker: JsIrLinker,
    val module: IrModuleFragment
) {

    private val signaturer = IdSignatureSerializer(JsManglerIr)
    private val globalDeclarationTable = JsGlobalDeclarationTable(signaturer, irBuiltIns)

    fun serializeDeclarations(declarations: Iterable<IrDeclaration>): SerializedIcData {

        // TODO serialize body carriers and new bodies as well
        val moduleDeserializer = linker.moduleDeserializer(module.descriptor)

        val fileToDeserializer = moduleDeserializer.fileDeserializers().associateBy { it.file }

        val filteredDeclarations = declarations.filter {
            when {
                it.fileOrNull.let { it == null || fileToDeserializer[it] == null } -> false
                it is IrFakeOverrideFunction -> it.isBound
                it is IrFakeOverrideProperty -> it.isBound
                else -> (it.parent as? IrFakeOverrideFunction)?.isBound ?: (it.parent as? IrFakeOverrideProperty)?.isBound ?: true
            }
        }

        val filteredBodies = irFactory.allBodies.groupBy {
            (it as? PersistentIrBodyBase<*>)?.let {
                if (it.hasContainer) {
                    it.container.fileOrNull
                } else null
            }
        }

        val dataToSerialize = filteredDeclarations.groupBy {
            // TODO don't move declarations or effects outside the original file
            // TODO Or invent a different mechanism for that

            it.file
        }

        val icData = mutableListOf<SerializedIcDataForFile>()

        for (file in fileToDeserializer.keys) {
            val declarations = dataToSerialize[file] ?: emptyList()
            val bodies = filteredBodies[file] ?: emptyList()

            val fileDeserializer = fileToDeserializer[file]!!



            val symbolToSignature = fileDeserializer.symbolDeserializer.deserializedSymbols.entries.associate { (idSig, symbol) -> symbol to idSig }.toMutableMap()

            val icDeclarationTable = IcDeclarationTable(globalDeclarationTable, irFactory, 1000000, 1000000, symbolToSignature)
            val fileSerializer = JsIrFileSerializer(IrMessageLogger.None, icDeclarationTable, mutableMapOf(), skipExpects = true, icMode = true, allowNullTypes = true)

            bodies.forEachIndexed { index, body ->
                if (body is IrExpressionBody) {
                    fileSerializer.serializeIrExpressionBody(body.expression)
                } else {
                    fileSerializer.serializeIrStatementBody(body)
                }
            }

            // Only save newly created declarations
            val newDeclarations = declarations.filter { d ->
                d is PersistentIrDeclarationBase<*> && (d.createdOn > 0 || d.isFakeOverride || (d is IrValueParameter || d is IrTypeParameter) && (d.parent as IrDeclaration).isFakeOverride)
            }

            val serializedCarriers = fileSerializer.serializeCarriers(
                declarations,
                bodies,
            ) { declaration ->
                icDeclarationTable.signatureByDeclaration(declaration)
            }

            val serializedMappings = mappings.state.serializeMappings(declarations) { symbol ->
                fileSerializer.serializeIrSymbol(symbol)
            }

            val order = storeOrder(file) { symbol ->
                fileSerializer.serializeIrSymbol(symbol)
            }

            val serializedIrFile = fileSerializer.serializeDeclarationsForIC(file, newDeclarations)

            icData += SerializedIcDataForFile(
                serializedIrFile,
                serializedCarriers,
                serializedMappings,
                order,
            )
        }

        return SerializedIcData(icData)
    }

    // Returns precomputed signatures for the newly created declarations. Delegates to the default table otherwise.
    class IcDeclarationTable(
        globalDeclarationTable: JsGlobalDeclarationTable,
        val irFactory: PersistentIrFactory,
        newLocalIndex: Long,
        newScopeIndex: Int,
        val existingMappings: MutableMap<IrSymbol, IdSignature>
    ) : DeclarationTable(globalDeclarationTable) {

        init {
            signaturer.reset(newLocalIndex, newScopeIndex)
        }

        override fun signatureByDeclaration(declaration: IrDeclaration): IdSignature {
            return existingMappings.getOrPut(declaration.symbol) {
                irFactory.declarationSignature(declaration) ?: super.signatureByDeclaration(declaration)
            }
        }
    }
}

class SerializedIcDataForFile(
    val file: SerializedIrFile,
    val carriers: SerializedCarriers,
    val mappings: SerializedMappings,
    val order: SerializedOrder,
)

class SerializedIcData(
    val files: Collection<SerializedIcDataForFile>,
)

class SerializedOrder(
    val topLevelSignatures: ByteArray,
    val containerSignatures: ByteArray,
    val declarationSignatures: ByteArray,
)

fun storeOrder(file: IrFile, idSigToLong: (IrSymbol) -> Long): SerializedOrder {
    val topLevelSignatures = mutableListOf<Long>()
    val containerSignatures = mutableListOf<Long>()
    val declarationSignatures = mutableListOf<ByteArray>()

    fun IrDeclaration.idSigIndex(): Long = idSigToLong(symbol)

    file.declarations.forEach { d ->
        topLevelSignatures += d.idSigIndex()
        d.acceptVoid(object : IrElementVisitorVoid {
            override fun visitElement(element: IrElement) {
                element.acceptChildrenVoid(this)
            }

            override fun visitClass(declaration: IrClass) {
                // First element is the container signature
                containerSignatures += declaration.idSigIndex()
                val declarationIds = declaration.declarations.map { it.idSigIndex() }

                declarationSignatures += IrMemoryLongArrayWriter(declarationIds).writeIntoMemory()

                super.visitClass(declaration)
            }
        })
    }

    return SerializedOrder(
        IrMemoryLongArrayWriter(topLevelSignatures).writeIntoMemory(),
        IrMemoryLongArrayWriter(containerSignatures).writeIntoMemory(),
        IrMemoryArrayWriter(declarationSignatures).writeIntoMemory(),
    )
}