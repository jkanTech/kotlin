interface IrElement

fun IrElement.dumpKotlinLike(options: String = ""): String = ""
fun IrElement.dump(normalizeNames: Boolean = false): String = ""

fun foo() {
    val dumpStrategy = System.getProperty("org.jetbrains.kotlin.compiler.ir.dump.strategy")
    val dump: IrElement.() -> String = if (dumpStrategy == "KotlinLike") IrElement::dumpKotlinLike else IrElement::dump
}
