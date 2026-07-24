package com.unite.sdk

interface SDKListener {
    fun onLoginSuccess(uid: String, token: String)
    fun onLoginFailed(message: String)
    fun onRegisterSuccess(uid: String)
    fun onRegisterFailed(message: String)
    fun onLogout()
}
