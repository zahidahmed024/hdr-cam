package com.example.hdrcamera.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.hdrcamera.model.FilterParameters
import com.example.hdrcamera.ui.theme.AccentGold
import com.example.hdrcamera.ui.theme.SurfaceDark
import com.example.hdrcamera.ui.theme.TextPrimary
import com.example.hdrcamera.ui.theme.TextSecondary

@Composable
fun SaveProfileDialog(
    currentParams: FilterParameters,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var profileName by remember { mutableStateOf("") }
    var isError by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = SurfaceDark),
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .fillMaxWidth()
            ) {
                Text(
                    text = "Save Custom HDR Profile",
                    color = TextPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Store current filter parameters (sharpness, highlight knee, contrast, LUT) as a quick preset.",
                    color = TextSecondary,
                    fontSize = 12.sp
                )

                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = profileName,
                    onValueChange = {
                        profileName = it
                        if (isError && it.isNotBlank()) isError = false
                    },
                    label = { Text("Profile Name") },
                    placeholder = { Text("e.g. Sunset Punch, Moody Street") },
                    singleLine = true,
                    isError = isError,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedBorderColor = AccentGold,
                        unfocusedBorderColor = Color(0xFF333842),
                        focusedLabelColor = AccentGold,
                        cursorColor = AccentGold
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                if (isError) {
                    Text(
                        text = "Name cannot be empty",
                        color = Color(0xFFFF5252),
                        fontSize = 11.sp,
                        modifier = Modifier.padding(top = 4.dp, start = 4.dp)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Quick summary chips
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Highlight Rec: ${String.format("%.2f", currentParams.highlightRecovery)}",
                        color = TextSecondary,
                        fontSize = 11.sp
                    )
                    Text(
                        text = "Sharpness: ${String.format("%.2f", currentParams.sharpness)}",
                        color = TextSecondary,
                        fontSize = 11.sp
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Contrast: ${String.format("%.2f", currentParams.contrast)}",
                        color = TextSecondary,
                        fontSize = 11.sp
                    )
                    Text(
                        text = "LUT: ${currentParams.lutId.replaceFirstChar { it.uppercase() }}",
                        color = AccentGold,
                        fontSize = 11.sp
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel", color = TextSecondary)
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Button(
                        onClick = {
                            if (profileName.trim().isEmpty()) {
                                isError = true
                            } else {
                                onSave(profileName.trim())
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = AccentGold)
                    ) {
                        Text("Save Profile", color = Color.Black, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}
