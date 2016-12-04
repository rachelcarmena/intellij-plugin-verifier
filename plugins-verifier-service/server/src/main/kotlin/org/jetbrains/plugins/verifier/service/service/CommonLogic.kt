package org.jetbrains.plugins.verifier.service.service

import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit

/**
 * @author Sergey Patrikeev
 */
fun makeClient(needLog: Boolean): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(5, TimeUnit.MINUTES)
    .readTimeout(5, TimeUnit.MINUTES)
    .writeTimeout(5, TimeUnit.MINUTES)
    .followRedirects(false)
    .addInterceptor(HttpLoggingInterceptor().setLevel(if (needLog) HttpLoggingInterceptor.Level.BASIC else HttpLoggingInterceptor.Level.NONE))
    .build()