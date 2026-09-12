package com.ailife.wftools.pc

import org.junit.Assert.assertEquals
import org.junit.Test

class EditorNoticeTest {
    @Test fun `ordinary success notice has only an acknowledge action`() {
        assertEquals(listOf("确定"), noticeActions(null))
    }

    @Test fun `script merge notice exposes the script action`() {
        assertEquals(listOf("查看脚本", "确定"), noticeActions(NoticeAction.SCRIPT))
    }

    @Test fun `multiline fields commit on plain enter but not shift enter`() {
        assertEquals(true, shouldCommitFieldEnter(multiline = true, shiftPressed = false))
        assertEquals(false, shouldCommitFieldEnter(multiline = true, shiftPressed = true))
        assertEquals(true, shouldCommitFieldEnter(multiline = false, shiftPressed = false))
    }
}
