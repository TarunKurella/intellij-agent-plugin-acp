package com.trono.agentplugin.toolwindow

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import javax.swing.JPanel

class AgentToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = AgentWebViewPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, "Agent", false)
        toolWindow.contentManager.addContent(content)
    }
}
