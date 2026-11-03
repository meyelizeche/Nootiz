package com.tridente.nootiz.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tridente.nootiz.data.local.SessionPreferences
import com.tridente.nootiz.data.model.AuthResponse
import com.tridente.nootiz.data.remote.NotizApiClient
import com.tridente.nootiz.ui.theme.ErrorRed
import com.tridente.nootiz.ui.theme.LimeAccent
import com.tridente.nootiz.ui.theme.NavyBackground
import com.tridente.nootiz.ui.theme.NavyInputBorder
import com.tridente.nootiz.ui.theme.NavyInputSurface
import com.tridente.nootiz.ui.theme.PureWhite
import com.tridente.nootiz.ui.theme.RoyalBlue
import com.tridente.nootiz.ui.theme.SkyBlueCard
import com.tridente.nootiz.ui.theme.SuccessGreen
import com.tridente.nootiz.ui.theme.TextMutedLight
import com.tridente.nootiz.ui.theme.TextPrimaryNavy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AuthScreen(
    sessionPrefs: SessionPreferences,
    apiClient: NotizApiClient,
    onAuthenticated: (AuthResponse) -> Unit
) {
    val scope = rememberCoroutineScope()

    var serverUrl by remember { mutableStateOf(SessionPreferences.DEFAULT_EMULATOR_URL) }
    var showServerPresets by remember { mutableStateOf(false) }
    var serverHealthStatus by remember { mutableStateOf<Boolean?>(null) }
    var checkingHealth by remember { mutableStateOf(false) }

    var isRegisterMode by remember { mutableStateOf(false) }
    var loginIdentifier by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }

    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var infoMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        val loadedUrl = withContext(Dispatchers.IO) {
            sessionPrefs.ensureLoaded()
            sessionPrefs.baseUrl
        }
        serverUrl = loadedUrl
    }

    fun verifyServerConnection(urlToCheck: String) {
        val normalized = SessionPreferences.normalizeBaseUrl(urlToCheck)
        serverUrl = normalized
        sessionPrefs.baseUrl = normalized
        checkingHealth = true
        serverHealthStatus = null
        scope.launch {
            val result = apiClient.checkHealth(normalized)
            checkingHealth = false
            serverHealthStatus = result.getOrDefault(false)
        }
    }

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedContainerColor = NavyInputSurface,
        unfocusedContainerColor = NavyInputSurface,
        focusedBorderColor = SkyBlueCard,
        unfocusedBorderColor = NavyInputBorder,
        focusedTextColor = PureWhite,
        unfocusedTextColor = PureWhite,
        cursorColor = LimeAccent,
        focusedPlaceholderColor = TextMutedLight.copy(alpha = 0.6f),
        unfocusedPlaceholderColor = TextMutedLight.copy(alpha = 0.6f)
    )

    Scaffold(
        containerColor = NavyBackground,
        modifier = Modifier.fillMaxSize()
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.Start
        ) {
            // Top Brand Icon (Upper-right mode switch button removed)
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(RoyalBlue),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Edit,
                    contentDescription = "Notiz",
                    tint = LimeAccent,
                    modifier = Modifier.size(22.dp)
                )
            }

            Spacer(modifier = Modifier.height(18.dp))

            // Title & Subtitle matching Image 1 & 3
            Text(
                text = if (isRegisterMode) "Crear perfil" else "Notiz",
                style = MaterialTheme.typography.headlineLarge,
                color = PureWhite
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = if (isRegisterMode) {
                    "Configura tu cuenta para empezar a organizar tus ideas."
                } else {
                    "Tus ideas, siempre contigo."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = TextMutedLight
            )

            Spacer(modifier = Modifier.height(18.dp))

            // Server / Base API URL Configuration Card
            Surface(
                color = NavyInputSurface.copy(alpha = 0.75f),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, NavyInputBorder, RoundedCornerShape(16.dp))
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = null,
                                tint = SkyBlueCard,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "URL Base del Servidor / API",
                                color = TextMutedLight,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        // Connection status indicator
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .clickable { verifyServerConnection(serverUrl) }
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        ) {
                            when {
                                checkingHealth -> {
                                    Text("Probando...", color = LimeAccent, fontSize = 11.sp)
                                }
                                serverHealthStatus == true -> {
                                    Icon(
                                        imageVector = Icons.Default.CheckCircle,
                                        contentDescription = null,
                                        tint = SuccessGreen,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Text(
                                        text = "API Online",
                                        color = SuccessGreen,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                                serverHealthStatus == false -> {
                                    Icon(
                                        imageVector = Icons.Default.Warning,
                                        contentDescription = null,
                                        tint = ErrorRed,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Text("Sin respuesta", color = ErrorRed, fontSize = 11.sp)
                                }
                                else -> {
                                    Text(
                                        text = "Probar conexi\u00f3n",
                                        color = SkyBlueCard,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = serverUrl,
                        onValueChange = {
                            serverUrl = it
                            serverHealthStatus = null
                        },
                        placeholder = { Text("http://10.0.2.2:3000") },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = PureWhite),
                        shape = RoundedCornerShape(12.dp),
                        colors = fieldColors,
                        trailingIcon = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(
                                    onClick = { verifyServerConnection(serverUrl) },
                                    modifier = Modifier.size(34.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Refresh,
                                        contentDescription = "Probar conexi\u00f3n",
                                        tint = LimeAccent,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                IconButton(
                                    onClick = { showServerPresets = !showServerPresets },
                                    modifier = Modifier.size(34.dp)
                                ) {
                                    Icon(
                                        imageVector = if (showServerPresets) {
                                            Icons.Default.KeyboardArrowUp
                                        } else {
                                            Icons.Default.KeyboardArrowDown
                                        },
                                        contentDescription = "Elegir servidor guardado",
                                        tint = TextMutedLight
                                    )
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )

                    AnimatedVisibility(visible = showServerPresets) {
                        Column(modifier = Modifier.padding(top = 10.dp)) {
                            Text(
                                text = "Elegir servidor r\u00e1pido:",
                                color = TextMutedLight,
                                fontSize = 11.sp
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                val presets = listOf(
                                    "Emulador" to "http://10.0.2.2:3000",
                                    "Localhost" to "http://localhost:3000",
                                    "LAN" to "http://192.168.1.100:3000"
                                )
                                presets.forEach { (label, presetUrl) ->
                                    val selected = SessionPreferences.normalizeBaseUrl(serverUrl) == presetUrl
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(50))
                                            .background(if (selected) RoyalBlue else NavyBackground)
                                            .border(
                                                1.dp,
                                                if (selected) SkyBlueCard else NavyInputBorder,
                                                RoundedCornerShape(50)
                                            )
                                            .clickable {
                                                verifyServerConnection(presetUrl)
                                            }
                                            .padding(horizontal = 10.dp, vertical = 5.dp)
                                    ) {
                                        Text(
                                            text = "$label ($presetUrl)",
                                            color = PureWhite,
                                            fontSize = 11.sp
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (isRegisterMode) {
                // Email Field (Register mode)
                Text(
                    text = "Email",
                    color = TextMutedLight,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(6.dp))
                OutlinedTextField(
                    value = email,
                    onValueChange = {
                        email = it
                        errorMessage = null
                    },
                    placeholder = { Text("hola@ejemplo.com") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Email,
                        imeAction = ImeAction.Next
                    ),
                    shape = RoundedCornerShape(14.dp),
                    colors = fieldColors,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Username Field (Register mode)
                Text(
                    text = "Username",
                    color = TextMutedLight,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(6.dp))
                OutlinedTextField(
                    value = username,
                    onValueChange = {
                        username = it
                        errorMessage = null
                    },
                    placeholder = { Text("Tu nombre de usuario") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Text,
                        imeAction = ImeAction.Next
                    ),
                    shape = RoundedCornerShape(14.dp),
                    colors = fieldColors,
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                // Single unified Correo / Usuario Field (Login mode)
                Text(
                    text = "Correo / Usuario",
                    color = TextMutedLight,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(6.dp))
                OutlinedTextField(
                    value = loginIdentifier,
                    onValueChange = {
                        loginIdentifier = it
                        errorMessage = null
                    },
                    placeholder = { Text("hola@ejemplo.com o tu usuario") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Text,
                        imeAction = ImeAction.Next
                    ),
                    shape = RoundedCornerShape(14.dp),
                    colors = fieldColors,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Password Field (matching Image 1 & 3)
            Text(
                text = "Password",
                color = TextMutedLight,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(6.dp))
            OutlinedTextField(
                value = password,
                onValueChange = {
                    password = it
                    errorMessage = null
                },
                placeholder = { Text("\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022") },
                singleLine = true,
                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done
                ),
                trailingIcon = {
                    TextButton(onClick = { passwordVisible = !passwordVisible }) {
                        Text(
                            text = if (passwordVisible) "Ocultar" else "Ver",
                            color = TextMutedLight,
                            fontSize = 11.sp
                        )
                    }
                },
                shape = RoundedCornerShape(14.dp),
                colors = fieldColors,
                modifier = Modifier.fillMaxWidth()
            )

            if (errorMessage != null) {
                Spacer(modifier = Modifier.height(12.dp))
                Surface(
                    color = ErrorRed.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = errorMessage ?: "",
                        color = ErrorRed,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            if (infoMessage != null) {
                Spacer(modifier = Modifier.height(12.dp))
                Surface(
                    color = SkyBlueCard.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = infoMessage ?: "",
                        color = SkyBlueCard,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Primary Lime Accent CTA Button ("Entrar" / "Crear cuenta")
            Button(
                onClick = {
                    errorMessage = null
                    infoMessage = null
                    sessionPrefs.baseUrl = serverUrl

                    if (isRegisterMode) {
                        if (username.trim().length < 3) {
                            errorMessage = "El nombre de usuario debe tener al menos 3 caracteres."
                            return@Button
                        }
                        if (!email.contains("@")) {
                            errorMessage = "Ingresa un correo electr\u00f3nico v\u00e1lido."
                            return@Button
                        }
                        if (password.length < 8) {
                            errorMessage = "La contrase\u00f1a debe tener m\u00ednimo 8 caracteres."
                            return@Button
                        }
                        isLoading = true
                        scope.launch {
                            val res = apiClient.register(username.trim(), email.trim(), password)
                            isLoading = false
                            res.onSuccess { auth ->
                                sessionPrefs.token = auth.token
                                sessionPrefs.user = auth.user
                                onAuthenticated(auth)
                            }.onFailure { err ->
                                errorMessage = err.message ?: "No se pudo registrar el usuario"
                            }
                        }
                    } else {
                        val identifier = loginIdentifier.trim()
                        if (identifier.isEmpty()) {
                            errorMessage = "Ingresa tu correo o tu nombre de usuario."
                            return@Button
                        }
                        if (password.isEmpty()) {
                            errorMessage = "Ingresa tu contrase\u00f1a."
                            return@Button
                        }
                        isLoading = true
                        scope.launch {
                            val res = apiClient.login(identifier, password)
                            isLoading = false
                            res.onSuccess { auth ->
                                sessionPrefs.token = auth.token
                                sessionPrefs.user = auth.user
                                onAuthenticated(auth)
                            }.onFailure { err ->
                                errorMessage = err.message ?: "Error al iniciar sesi\u00f3n"
                            }
                        }
                    }
                },
                enabled = !isLoading,
                shape = RoundedCornerShape(50),
                colors = ButtonDefaults.buttonColors(
                    containerColor = LimeAccent,
                    contentColor = TextPrimaryNavy,
                    disabledContainerColor = LimeAccent.copy(alpha = 0.5f)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                Text(
                    text = when {
                        isLoading -> "Conectando..."
                        isRegisterMode -> "Crear cuenta"
                        else -> "Entrar"
                    },
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimaryNavy
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Secondary links matching reference image:
            // "\u00bfOlvidaste tu contrase\u00f1a?"
            // "\u00bfNo tienes una cuenta? Crear cuenta"
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (!isRegisterMode) {
                    Text(
                        text = "\u00bfOlvidaste tu contrase\u00f1a?",
                        color = TextMutedLight,
                        fontSize = 12.sp,
                        modifier = Modifier.clickable {
                            infoMessage = "Contacta al administrador del servidor para restablecer tu contrase\u00f1a."
                        }
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (isRegisterMode) "\u00bfYa tienes una cuenta? " else "\u00bfNo tienes una cuenta? ",
                        color = TextMutedLight,
                        fontSize = 12.sp
                    )
                    Text(
                        text = if (isRegisterMode) "Iniciar sesi\u00f3n" else "Crear cuenta",
                        color = PureWhite,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        textDecoration = TextDecoration.Underline,
                        modifier = Modifier.clickable {
                            isRegisterMode = !isRegisterMode
                            errorMessage = null
                            infoMessage = null
                        }
                    )
                }
            }
        }
    }
}
