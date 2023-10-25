package com.nabto.edge.sharedcode

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import androidx.room.Room
import com.amplifyframework.auth.cognito.AWSCognitoAuthPlugin
import com.nabto.edge.client.NabtoClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import org.koin.android.ext.koin.androidApplication
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import org.koin.dsl.module
import com.amplifyframework.core.Amplify
import com.amplifyframework.core.AmplifyConfiguration
import org.json.JSONObject

// @TODO: You should change this configuration to suit your needs.
object AppConfig {
    /** App name that is returned from GET /iam/pairing (https://docs.nabto.com/developer/api-reference/coap/iam/pairing.html) */
    const val DEVICE_APP_NAME = "edge_smarthome_webrtc"

    /** MDNS sub type that NabtoDeviceScanner will use to search for devices on the local network */
    const val MDNS_SUB_TYPE = "edgewebrtc"

    // Shared preferences keys
    const val PRIVATE_KEY_PREF = "client_private_key"
    const val DISPLAY_NAME_PREF = "nabto_display_name"

    /** Nabto server key, this is retrieved from the App page in the Nabto cloud console. */
    const val SERVER_KEY = "sk-d8254c6f790001003d0c842d1b63b134"

    /** Our cloud service that we get/post info from/to */
    const val CLOUD_SERVICE_URL = "https://smarthome.as.dev.nabto.com"

    /** Cognito userpool config */
    const val COGNITO_REGION = "eu-west-1"
    const val COGNITO_POOL_ID = "eu-west-1_yaALYWqtM"
    const val COGNITO_APP_CLIENT_ID = "58kbjihv7mq5tdutlm09ktkd9c"
    const val COGNITO_WEB_DOMAIN = "as-oauth-example.auth.eu-west-1.amazoncognito.com"
}

private val amplifyJsonString = """
    {
      "auth": {
        "plugins": {
          "awsCognitoAuthPlugin": {
            "IdentityManager": {
              "Default": {}
            },
            "CognitoUserPool": {
              "Default": {
                "PoolId": "${AppConfig.COGNITO_POOL_ID}",
                "AppClientId": "${AppConfig.COGNITO_APP_CLIENT_ID}",
                "Region": "${AppConfig.COGNITO_REGION}"
              }
            },
            "Auth": {
              "Default": {
                "authenticationFlowType": "USER_SRP_AUTH",
                "OAuth": {
                  "WebDomain": "${AppConfig.COGNITO_WEB_DOMAIN}",
                  "AppClientId": "${AppConfig.COGNITO_APP_CLIENT_ID}",
                  "SignInRedirectURI": "myapp://example",
                  "SignOutRedirectURI": "myapp://example",
                  "Scopes": [
                    "phone",
                    "email",
                    "openid",
                    "profile",
                    "aws.cognito.signin.user.admin"
                  ]
                }
              }
            }
          }
        }
      }
    }
""".trimIndent()

private fun appModule(client: NabtoClient, scanner: NabtoDeviceScanner, scope: CoroutineScope, connectivityManager: ConnectivityManager) =
    module {
        single {
            Room.databaseBuilder(
                androidApplication(),
                DeviceDatabase::class.java,
                "device-database"
            ).build()
        }

        single<NabtoRepository> { NabtoRepositoryImpl(androidApplication(), client, scope, scanner) }

        single<NabtoConnectionManager> {
            NabtoConnectionManagerImpl(get(), client, connectivityManager)
        }

        single<LoginProvider> {
            CognitoLoginProvider(scope)
        }

        single<NabtoBookmarksRepository> {
            NabtoBookmarksRepositoryImpl(get(), get(), get(), scope)
        }
    }

open class NabtoAndroidApplication : Application() {
    private val nabtoClient: NabtoClient by lazy { NabtoClient.create(this) }
    private val scanner: NabtoDeviceScanner by lazy { NabtoDeviceScanner(nabtoClient) }
    private val applicationScope = CoroutineScope(SupervisorJob())

    override fun onCreate() {
        super.onCreate()

        // Amplify setup
        Amplify.addPlugin(AWSCognitoAuthPlugin())
        val amplifyConfig = AmplifyConfiguration.fromJson(JSONObject(amplifyJsonString))
        Amplify.configure(amplifyConfig, this)

        val connectivityManager = applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        startKoin {
            androidLogger()
            androidContext(this@NabtoAndroidApplication)
            modules(appModule(nabtoClient, scanner, applicationScope, connectivityManager))
        }
    }
}