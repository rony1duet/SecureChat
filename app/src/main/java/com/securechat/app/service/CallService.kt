package com.securechat.app.service

import android.app.Application
import com.zegocloud.uikit.prebuilt.call.ZegoUIKitPrebuiltCallService
import com.zegocloud.uikit.prebuilt.call.invite.ZegoUIKitPrebuiltCallInvitationConfig
import com.zegocloud.uikit.prebuilt.call.invite.ZegoUIKitPrebuiltCallInvitationService

object CallService {
    fun init(application: Application, appID: Long, appSign: String, userID: String, userName: String) {
        val config = ZegoUIKitPrebuiltCallInvitationConfig()
        ZegoUIKitPrebuiltCallInvitationService.init(application, appID, appSign, userID, userName, config)
    }

    fun unInit() {
        ZegoUIKitPrebuiltCallInvitationService.unInit()
    }
}
