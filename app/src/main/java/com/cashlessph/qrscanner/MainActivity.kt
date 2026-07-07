package com.cashlessph.qrscanner

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.room.*
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors

@Entity(tableName = "qr_list")
data class QrEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val rawValue: String,
    val merchantName: String = "Unknown Merchant",
    val timestamp: Long = System.currentTimeMillis()
)

@Dao
interface QrDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(qr: QrEntity)

    @Query("SELECT * FROM qr_list ORDER BY timestamp DESC LIMIT 25")
    fun getAllQr(): Flow<List<QrEntity>>

    @Query("DELETE FROM qr_list WHERE id = :id")
    suspend fun deleteById(id: Int)

    @Query("SELECT COUNT(*) FROM qr_list")
    suspend fun getCount(): Int

    @Query("DELETE FROM qr_list WHERE id IN (SELECT id FROM qr_list ORDER BY timestamp ASC LIMIT 1)")
    suspend fun deleteOldest()
}

@Database(entities = [QrEntity::class], version = 1)
abstract class AppDatabase : RoomDatabase() {
    abstract fun qrDao(): QrDao
}

data class EWallet(
    val name: String,
    val gradientColors: List<Color>,
    val packageName: String,
    val logoText: String,
    val logoRes: Int
)

class MainActivity : ComponentActivity() {

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) {
            Toast.makeText(this, "Need camera permission", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val db = Room.databaseBuilder(
            applicationContext,
            AppDatabase::class.java, "qr-database"
        ).build()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }

        setContent {
            val navController = rememberNavController()
            NavHost(navController = navController, startDestination = "scanner") {
                composable("scanner") { QrScannerScreen(db, navController) }
                composable("qr_list") { QrListScreen(db, navController) }
                composable("settings") { SettingsScreen(navController) }
                composable("about") { AboutScreen(navController) }
                composable("privacy") { PrivacyPolicyScreen(navController) }
                composable("payment_select/{qrData}") { backStackEntry ->
                    val qrData = backStackEntry.arguments?.getString("qrData") ?: ""
                    PaymentSelectScreen(navController, qrData)
                }
            }
        }
    }
}

// ------------------------------------------------------------------
// QR + Merchant detection helpers (these were missing before)
// ------------------------------------------------------------------
private val qrScanner = BarcodeScanning.getClient(
    BarcodeScannerOptions.Builder()
        .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
        .build()
)
@OptIn(ExperimentalGetImage::class)
fun processImageProxy(imageProxy: ImageProxy, onResult: (String?) -> Unit) {
    val mediaImage = imageProxy.image
    if (mediaImage != null) {
        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        qrScanner.process(image)
            .addOnSuccessListener { barcodes ->
                val value = barcodes.firstOrNull()?.rawValue
                onResult(value)
            }
            .addOnFailureListener {
                onResult(null)
            }
            .addOnCompleteListener {
                imageProxy.close()
            }
    } else {
        imageProxy.close()
        onResult(null)
    }
}

fun detectMerchant(qrData: String): String {
    return when {
        qrData.contains("gcash", ignoreCase = true) -> "GCash Merchant"
        qrData.contains("maya", ignoreCase = true) -> "Maya Merchant"
        qrData.contains("grab", ignoreCase = true) -> "GrabPay Merchant"
        qrData.contains("shopee", ignoreCase = true) -> "ShopeePay Merchant"
        else -> "Unknown Merchant"
    }
}

@Composable
fun QrScannerScreen(db: AppDatabase, navController: NavController) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var isProcessing by remember { mutableStateOf(false) }
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                val previewView = PreviewView(ctx)
                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)

                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }

                    val imageAnalyzer = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                        .also {
                            it.setAnalyzer(cameraExecutor) { imageProxy ->
                                processImageProxy(imageProxy) { result ->
                                    if (result != null && !isProcessing) {
                                        isProcessing = true
                                        val merchant = detectMerchant(result)

                                        CoroutineScope(Dispatchers.IO).launch {
                                            // MAX 25 LANG - DELETE OLDEST PAG SUMOBRA
                                            val count = db.qrDao().getCount()
                                            if (count >= 25) {
                                                db.qrDao().deleteOldest()
                                            }
                                            db.qrDao().insert(
                                                QrEntity(
                                                    rawValue = result,
                                                    merchantName = merchant
                                                )
                                            )
                                        }

                                        // REDIRECT AGAD SA PAYMENT SELECT
                                        navController.navigate("payment_select/${Uri.encode(result)}")

                                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                            isProcessing = false
                                        }, 2000)
                                    }
                                }
                            }
                        }

                    val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                    try {
                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner, cameraSelector, preview, imageAnalyzer
                        )
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }, ContextCompat.getMainExecutor(ctx))
                previewView
            },
            modifier = Modifier.fillMaxSize()
        )

        // Scanner viewfinder frame + instruction
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size(260.dp)
                .border(3.dp, Color(0xFFFF4D8D), RoundedCornerShape(24.dp))
        )

        Text(
            text = "Point camera at QRPH code",
            color = Color(0xFFFF4D8D),
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .align(Alignment.Center)
                .padding(top = 300.dp)
        )

        IconButton(
            onClick = { navController.navigate("settings") },
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 16.dp, top = 24.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Settings,
                contentDescription = "Settings",
                tint = Color.White,
                modifier = Modifier.size(32.dp)
            )
        }

        IconButton(
            onClick = { navController.navigate("qr_list") },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(end = 16.dp, top = 24.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Help,
                contentDescription = "QR List",
                tint = Color.White,
                modifier = Modifier.size(32.dp)
            )
        }
    }
}

@Composable
fun QrListScreen(db: AppDatabase, navController: NavController) {
    val qrList by db.qrDao().getAllQr().collectAsState(initial = emptyList())
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF121212))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 16.dp, top = 40.dp, bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { navController.popBackStack() }) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
            }
            Text("Saved QR Codes (${qrList.size}/25)", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Medium)
        }

        if (qrList.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No saved QR yet\nScan a merchant QR to save", color = Color.Gray, fontSize = 16.sp)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(qrList) { qr ->
                    QrListItem(qr = qr, onDelete = {
                        scope.launch {
                            db.qrDao().deleteById(qr.id)
                            Toast.makeText(context, "Deleted", Toast.LENGTH_SHORT).show()
                        }
                    })
                }
            }
        }
    }
}

@Composable
fun QrListItem(qr: QrEntity, onDelete: () -> Unit) {
    val dateFormat = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
    val date = dateFormat.format(Date(qr.timestamp))

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF1E1E1E), RoundedCornerShape(12.dp))
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .background(Color(0xFF3D1A2B), RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text("QR", color = Color(0xFFFF4D8D), fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }

        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(text = qr.merchantName, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Text(text = date, color = Color.Gray, fontSize = 13.sp)
        }

        IconButton(onClick = onDelete) {
            Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color(0xFFFF4D8D))
        }
    }
}

@Composable
fun SettingsScreen(navController: NavController) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0A0A))
    ) {
        Image(
            painter = painterResource(id = R.drawable.settings_bg),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            alpha = 0.35f
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF0A0A0A).copy(alpha = 0.55f))
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(300.dp)
                .align(Alignment.BottomCenter)
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color(0xFF1A0A1F).copy(alpha = 0.8f), Color(0xFF2D0F3D).copy(alpha = 0.9f))
                    )
                )
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
                .align(Alignment.BottomCenter)
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(Color(0xFFFF4D8D).copy(alpha = 0.3f), Color.Transparent),
                        radius = 400f
                    )
                )
        )

        Text(
            text = "UR",
            color = Color(0xFF2D0F3D),
            fontSize = 120.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 60.dp)
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 24.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { navController.popBackStack() }) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color(0xFFFF4D8D), modifier = Modifier.size(28.dp))
                }

                Box(
                    modifier = Modifier
                        .border(1.dp, Color(0xFFFF4D8D), RoundedCornerShape(8.dp))
                        .background(Color(0xFF1A0A1F), RoundedCornerShape(8.dp))
                        .clickable { navController.navigate("qr_list") }
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text("QR LIST", color = Color(0xFFFF4D8D), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
            }

            Text(text = "Settings", color = Color(0xFFFF4D8D), fontSize = 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
            Text(text = "Manage your app preferences", color = Color.Gray, fontSize = 14.sp, modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 24.dp), textAlign = TextAlign.Center)

            SettingsItem(icon = "📊", title = "Contact Us", subtitle = "Get in touch with our support team", iconBgColor = Color(0xFF3D1A2B), onClick = { })
            Spacer(modifier = Modifier.height(12.dp))
            SettingsItem(icon = "🔒", title = "Privacy Policy", subtitle = "Read our privacy policy\nand data practices", iconBgColor = Color(0xFF3D1A2B), onClick = { navController.navigate("privacy") })
            Spacer(modifier = Modifier.height(12.dp))
            SettingsItem(icon = "ℹ", title = "About UR Scanner", subtitle = "Learn more about the app\nand its mission", iconBgColor = Color(0xFF3D1A2B), onClick = { navController.navigate("about") })
            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF1A1A1A), RoundedCornerShape(16.dp))
                    .border(1.dp, Color(0xFF2A2A2A), RoundedCornerShape(16.dp))
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Version", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    Text("1.0.0", color = Color(0xFFFF4D8D), fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 4.dp))
                    Text("You're using the latest version", color = Color.Gray, fontSize = 13.sp)
                }

                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .background(Color(0xFF3D1A2B), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Box(modifier = Modifier.size(40.dp).border(2.dp, Color(0xFFFF4D8D), CircleShape))
                    Text("✓", color = Color(0xFFFF4D8D), fontSize = 24.sp, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 32.dp)
                    .border(1.dp, Color(0xFF2A2A2A), RoundedCornerShape(12.dp))
                    .background(Color(0xFF1A1A1A), RoundedCornerShape(12.dp))
                    .clickable { navController.popBackStack() }
                    .padding(vertical = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("✕", color = Color(0xFFFF4D8D), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Close Settings", color = Color(0xFFFF4D8D), fontSize = 16.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

@Composable
fun SettingsItem(icon: String, title: String, subtitle: String, iconBgColor: Color, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF1A1A1A), RoundedCornerShape(16.dp))
            .border(1.dp, Color(0xFF2A2A2A), RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.size(48.dp).background(iconBgColor, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
            Text(text = icon, fontSize = 24.sp)
        }
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Text(text = subtitle, color = Color.Gray, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
        }
        Text(text = ">", color = Color(0xFFFF4D8D), fontSize = 24.sp, fontWeight = FontWeight.Light)
    }
}

@Composable
fun AboutScreen(navController: NavController) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0A0A))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 16.dp, top = 40.dp, bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { navController.popBackStack() }) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color(0xFFFF4D8D), modifier = Modifier.size(28.dp))
            }
            Text("About UR Scanner", color = Color(0xFFFF4D8D), fontSize = 20.sp, fontWeight = FontWeight.Medium)
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            item {
                Box(modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
                    Box(
                        modifier = Modifier
                            .size(100.dp)
                            .border(3.dp, Color(0xFFFF4D8D), RoundedCornerShape(16.dp))
                            .padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("QR", color = Color(0xFFFF4D8D), fontSize = 32.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            item { AboutRow(icon = Icons.Default.QrCode, text = "UR Scanner is a QRPH reader that helps you open QR codes in your preferred bank or e-wallet app.") }
            item { Divider(color = Color(0xFF2A2A2A), thickness = 1.dp) }
            item { AboutRow(icon = Icons.Default.Shield, text = "This app does not process payments, store funds, or hold user money.\nIt only redirects to your chosen app.") }
            item { Divider(color = Color(0xFF2A2A2A), thickness = 1.dp) }
            item { AboutRow(icon = Icons.Default.Info, text = "*Clicks only. Not confirmed payment.\nYou will choose amount inside your app.") }
            item { Box(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).height(1.dp).background(Color(0xFFFF4D8D))) }

            item {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    Box(modifier = Modifier.size(48.dp).background(Color(0xFF3D1A2B), CircleShape), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Person, contentDescription = "Developer", tint = Color(0xFFFF4D8D), modifier = Modifier.size(24.dp))
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Column {
                        Text("Developer:", color = Color(0xFFFF4D8D), fontSize = 14.sp)
                        Text("EDISON SUCLATAN DAYAGUIT", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 4.dp))
                        Text("San Antonio Adtuyon\nPangantucan Bukidnon", color = Color.Gray, fontSize = 14.sp, lineHeight = 20.sp)
                    }
                }
            }

            item { Divider(color = Color(0xFF2A2A2A), thickness = 1.dp) }
            item { AboutRow(icon = Icons.Default.Public, text = "UR Scanner is not affiliated with any bank or e-wallet.") }
            item { Divider(color = Color(0xFF2A2A2A), thickness = 1.dp) }

            item {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    Box(modifier = Modifier.size(48.dp).background(Color(0xFF3D1A2B), CircleShape), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Favorite, contentDescription = "Heart", tint = Color(0xFFFF4D8D), modifier = Modifier.size(24.dp))
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Text("Yun lang para maintindihan nyo talaga\nand mabuhay kayo 3x a day\nmga ka inspirasyon ♥", color = Color.White, fontSize = 15.sp, lineHeight = 22.sp)
                }
            }

            item { Spacer(modifier = Modifier.height(24.dp)) }

            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 32.dp)
                        .border(1.dp, Color(0xFF2A2A2A), RoundedCornerShape(12.dp))
                        .background(Color(0xFF1A1A1A), RoundedCornerShape(12.dp))
                        .clickable { navController.popBackStack() }
                        .padding(vertical = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("✕", color = Color(0xFFFF4D8D), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Close", color = Color(0xFFFF4D8D), fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }
}

@Composable
fun AboutRow(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Box(modifier = Modifier.size(48.dp).background(Color(0xFF3D1A2B), CircleShape), contentAlignment = Alignment.Center) {
            Icon(imageVector = icon, contentDescription = null, tint = Color(0xFFFF4D8D), modifier = Modifier.size(24.dp))
        }
        Spacer(modifier = Modifier.width(16.dp))
        Text(text = text, color = Color.White, fontSize = 15.sp, lineHeight = 22.sp, modifier = Modifier.weight(1f))
    }
}

@Composable
fun PrivacyPolicyScreen(navController: NavController) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0A0A))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 16.dp, top = 40.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { navController.popBackStack() }) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color(0xFFFF4D8D), modifier = Modifier.size(28.dp))
            }

            Text("Privacy Policy", color = Color(0xFFFF4D8D), fontSize = 20.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)

            IconButton(onClick = { navController.popBackStack() }) {
                Text("✕", color = Color(0xFFFF4D8D), fontSize = 24.sp, fontWeight = FontWeight.Bold)
            }
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            item {
                Box(modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
                    Box(
                        modifier = Modifier
                            .size(80.dp)
                            .background(Color(0xFF3D1A2B), CircleShape)
                            .border(2.dp, Color(0xFFFF4D8D), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Lock, contentDescription = "Privacy", tint = Color(0xFFFF4D8D), modifier = Modifier.size(40.dp))
                    }
                }
            }

            item {
                Text("Privacy Policy", color = Color(0xFFFF4D8D), fontSize = 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                Text("Last updated: May 20, 2024", color = Color.Gray, fontSize = 14.sp, modifier = Modifier.fillMaxWidth().padding(top = 4.dp), textAlign = TextAlign.Center)
            }

            item {
                Text("Your privacy is important to us. This Privacy Policy explains how UR Scanner collects, uses, and protects your information.", color = Color.White, fontSize = 15.sp, lineHeight = 22.sp, modifier = Modifier.padding(top = 8.dp))
            }

            item { PrivacySection(number = "1.", title = "Information We Collect", bullets = listOf("We do not collect personal information such as name, email, or phone number.", "We only collect anonymous data for analytics purposes (e.g., scan counts, device info).")) }
            item { PrivacySection(number = "2.", title = "How We Use Information", bullets = listOf("We use the data to improve app performance and user experience.")) }
            item { PrivacySection(number = "3.", title = "Data Sharing", bullets = listOf("We do not share your data with any third parties.")) }
            item { PrivacySection(number = "4.", title = "Data Security", bullets = listOf("We implement appropriate security measures to protect your data.")) }
            item { PrivacySection(number = "5.", title = "Your Choices", bullets = listOf("You can clear app data anytime through your device settings.")) }
            item { Spacer(modifier = Modifier.height(24.dp)) }

            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 32.dp)
                        .border(1.dp, Color(0xFF2A2A2A), RoundedCornerShape(12.dp))
                        .background(Color(0xFF1A1A1A), RoundedCornerShape(12.dp))
                        .clickable { navController.popBackStack() }
                        .padding(vertical = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("✕", color = Color(0xFFFF4D8D), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Close", color = Color(0xFFFF4D8D), fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }
}

@Composable
fun PrivacySection(number: String, title: String, bullets: List<String>) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row {
            Text(text = number, color = Color(0xFFFF4D8D), fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = title, color = Color(0xFFFF4D8D), fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.height(8.dp))

        bullets.forEach { bullet ->
            Row(modifier = Modifier.padding(start = 16.dp, top = 4.dp), verticalAlignment = Alignment.Top) {
                Text("•", color = Color(0xFFFF4D8D), fontSize = 16.sp, modifier = Modifier.padding(end = 8.dp, top = 2.dp))
                Text(text = bullet, color = Color.White, fontSize = 15.sp, lineHeight = 22.sp, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
fun PaymentSelectScreen(navController: NavController, qrData: String) {
    val context = LocalContext.current

    val wallets = listOf(
        EWallet(name = "GCash", gradientColors = listOf(Color(0xFF1A0B2E), Color(0xFF4A1B6B)), packageName = "com.globe.gcash.android", logoText = "G", logoRes = R.drawable.gcash),
        EWallet(name = "Maya", gradientColors = listOf(Color(0xFF0A2E1A), Color(0xFF1B6B3A)), packageName = "com.paymaya", logoText = "maya", logoRes = R.drawable.maya),
        EWallet(name = "GrabPay", gradientColors = listOf(Color(0xFF0A2E1A), Color(0xFF1B6B3A)), packageName = "com.grabtaxi.passenger", logoText = "Grab", logoRes = R.drawable.grab),
        EWallet(name = "ShopeePay", gradientColors = listOf(Color(0xFF2E0A0A), Color(0xFF6B1B1B)), packageName = "com.shopee.ph", logoText = "S", logoRes = R.drawable.shopee)
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF000000))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 24.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .border(2.dp, Color(0xFFFF4D8D), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text("?", color = Color(0xFFFF4D8D), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                }

                Row {
                    Text("SELECT ", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text("YOUR", color = Color(0xFFFF4D8D), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text(" PREFERRED", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }

                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .border(2.dp, Color(0xFFFF4D8D), CircleShape)
                        .clickable { navController.popBackStack() },
                    contentAlignment = Alignment.Center
                ) {
                    Text("✕", color = Color(0xFFFF4D8D), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .border(1.dp, Color(0xFFFF4D8D), RoundedCornerShape(16.dp))
                    .background(Color(0xFF0A0A0A), RoundedCornerShape(16.dp))
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Search, contentDescription = "Search", tint = Color(0xFFFF4D8D), modifier = Modifier.size(24.dp))
                    Spacer(modifier = Modifier.width(12.dp))
                    Text("Search e-wallet or bank", color = Color.Gray, fontSize = 15.sp)
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.weight(1f)
            ) {
                items(wallets) { wallet ->
                    EWalletCard(wallet = wallet, onClick = {
                        val launchIntent = context.packageManager.getLaunchIntentForPackage(wallet.packageName)
                        if (launchIntent != null) {
                            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            context.startActivity(launchIntent)
                        } else {
                            try {
                                val marketIntent = Intent(
                                    Intent.ACTION_VIEW,
                                    Uri.parse("market://details?id=${wallet.packageName}")
                                )
                                marketIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                context.startActivity(marketIntent)
                            } catch (e: Exception) {
                                val webIntent = Intent(
                                    Intent.ACTION_VIEW,
                                    Uri.parse("https://play.google.com/store/apps/details?id=${wallet.packageName}")
                                )
                                webIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                context.startActivity(webIntent)
                            }
                        }
                    })
                }
            }
        }
    }
}

@Composable
fun EWalletCard(wallet: EWallet, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .border(1.dp, Color(0xFFFF4D8D), RoundedCornerShape(16.dp))
            .background(
                brush = Brush.verticalGradient(wallet.gradientColors),
                shape = RoundedCornerShape(16.dp)
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .background(Color.White, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(id = wallet.logoRes),
                    contentDescription = "${wallet.name} logo",
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Fit
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = wallet.name,
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
