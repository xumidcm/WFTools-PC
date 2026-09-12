package com.ailife.wftools.pc

import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.ailife.wftools.pc.core.WatchDocumentStore
import com.google.gson.Gson
import kotlinx.coroutines.*
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

data class KnownUiCoverage(val component: String,val source: String,val scope: String,val bindings: List<String>,val keys: List<String>,val entryTag: String,val fieldPrefix: String,val notes: String) {
    override fun toString() = component
}

/** Executable upstream coverage inventory. Tags belong on real dedicated controls, not raw fallback rows. */
@RunWith(Parameterized::class)
class KnownUiCoverageRedTest(private val row: KnownUiCoverage,private val layerType: String) {
    @get:Rule val compose = createComposeRule()
    @get:Rule val temp = TemporaryFolder()
    @Test fun `upstream known options are offered outside the raw attribute fallback`(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val store = WatchDocumentStore(temp.newFolder().toPath()); val session = store.newDocument()
        if(row.scope == "layer") {
            session.addLayer(layerType)
            // Activate the conditional shader/outline/shadow branches without inventing any resources.
            val seeds = mapOf("shader" to "ProgressBetween","outline" to "Outline","shadow" to "Glow","color_dim" to "112233","stencil" to "y","transform" to "uc","anim_in" to "Fade","anim_out" to "Fade") + (1..5).associate { "u_$it" to "50" }
            seeds.forEach { (key,value) -> session.setAttribute(session.selection.selected,key,value) }
        }
        val doc = DocumentController.create(session,store,scope,EditorPreferences(temp.newFolder().toPath()))
        doc.render.playing = false
        try {
            compose.setContent { WFToolsTheme(false) { Box(Modifier.width(420.dp).height(800.dp)) {
                if(row.scope == "layer") PropertiesPanel(doc,DropRegistry(),Modifier.fillMaxSize()) else ProjectPanel(doc,Modifier.fillMaxSize())
            } } }
            if(row.scope == "project-section") compose.onNodeWithTag(row.entryTag).assertExists()
            else {
                fun missingControls() = row.keys.filter { key -> compose.onAllNodesWithTag(row.fieldPrefix+key).fetchSemanticsNodes().isEmpty() }
                var missing = missingControls()
                val section = compose.onAllNodesWithTag(row.entryTag).fetchSemanticsNodes().singleOrNull()
                if(missing.isNotEmpty() && section?.config?.contains(SemanticsActions.OnClick) == true) {
                    compose.onNodeWithTag(row.entryTag).performScrollTo().performClick()
                    missing = missingControls()
                }
                assertTrue("${row.component} [$layerType] is missing dedicated known controls $missing. Upstream: ${row.source}. Advanced raw attributes do not satisfy this contract.",missing.isEmpty())
            }
        } finally { doc.render.close(); session.close(); scope.cancel() }
    }
    companion object {
        @JvmStatic @Parameterized.Parameters(name="{0} / {1}") fun entries(): List<Array<Any>> {
            val source = requireNotNull(KnownUiCoverageRedTest::class.java.getResourceAsStream("/contracts/upstream-ui-coverage.json"))
            val rows = source.bufferedReader().use { Gson().fromJson(it,Array<KnownUiCoverage>::class.java) }
            return rows.flatMap { row -> row.bindings.map { arrayOf(row,it) } }
        }
    }
}
