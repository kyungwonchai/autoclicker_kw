package com.kwandsoft.autoclicker

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.kwandsoft.autoclicker.service.AutoClickAccessibilityService
import com.kwandsoft.autoclicker.service.FloatingControlService

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AutoClickerTheme {
                MainScreen()
            }
        }
    }
}

@Composable
fun AutoClickerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFF2196F3),
            secondary = Color(0xFF03DAC6),
            background = Color(0xFF121212),
            surface = Color(0xFF1E1E1E),
            onPrimary = Color.White,
            onBackground = Color(0xFFE0E0E0),
            onSurface = Color.White
        ),
        content = content
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    val context = LocalContext.current
    var hasOverlayPermission by remember { mutableStateOf(checkOverlayPermission(context)) }
    var hasAccessibilityPermission by remember { mutableStateOf(AutoClickAccessibilityService.isServiceConnected) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        hasOverlayPermission = checkOverlayPermission(context)
        hasAccessibilityPermission = AutoClickAccessibilityService.isServiceConnected
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("⚡ KW 오토클리커 (AutoClicker)", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "필수 권한 설정",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )

            // 1. 오버레이 권한 카드
            PermissionCard(
                title = "1. 다른 앱 위에 표시 (오버레이) 권한",
                description = "다른 앱이나 화면 위에 플로팅 컨트롤러와 클릭 타겟을 띄우기 위해 필요합니다.",
                isGranted = hasOverlayPermission,
                icon = Icons.Default.Layers,
                onGrantClick = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        val intent = Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${context.packageName}")
                        )
                        context.startActivity(intent)
                    }
                }
            )

            // 2. 접근성 권한 카드
            PermissionCard(
                title = "2. 접근성(Accessibility) 서비스 활성화",
                description = "화면의 지정된 위치를 자동으로 터치(클릭/길게 누르기)하기 위해 필요합니다.",
                isGranted = hasAccessibilityPermission,
                icon = Icons.Default.Accessibility,
                onGrantClick = {
                    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    context.startActivity(intent)
                }
            )

            Spacer(modifier = Modifier.weight(1f))

            // 플로팅 메뉴 실행 버튼
            Button(
                onClick = {
                    if (!hasOverlayPermission) {
                        Toast.makeText(context, "다른 앱 위에 표시 권한을 먼저 허용해주세요.", Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    if (!hasAccessibilityPermission) {
                        Toast.makeText(context, "접근성 서비스를 먼저 켜주세요.", Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    FloatingControlService.start(context)
                    Toast.makeText(context, "플로팅 컨트롤러가 실행되었습니다. 홈 화면이나 원하는 앱으로 이동하세요!", Toast.LENGTH_LONG).show()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (hasOverlayPermission && hasAccessibilityPermission)
                        Color(0xFF4CAF50) else Color.Gray
                )
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("플로팅 컨트롤러 시작하기", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun PermissionCard(
    title: String,
    description: String,
    isGranted: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onGrantClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (isGranted) Color(0xFF4CAF50) else Color(0xFFFF9800)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = if (isGranted) "완료" else "필요",
                    color = if (isGranted) Color(0xFF4CAF50) else Color(0xFFFF9800),
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
            }

            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = Color.LightGray
            )

            if (!isGranted) {
                Button(
                    onClick = onGrantClick,
                    modifier = Modifier.align(Alignment.End),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text("권한 허용하러 가기", fontSize = 12.sp)
                }
            }
        }
    }
}

fun checkOverlayPermission(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        Settings.canDrawOverlays(context)
    } else {
        true
    }
}
