package dev.morrow

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.morrow.ui.MorrowRoot
import dev.morrow.ui.theme.MorrowTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val graph = (application as MorrowApp).graph
        setContent {
            MorrowTheme {
                MorrowRoot(graph)
            }
        }
    }
}
