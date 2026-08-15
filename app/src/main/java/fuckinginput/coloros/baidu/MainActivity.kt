package fuckinginput.coloros.baidu

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import fuckinginput.coloros.baidu.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
    }

    override fun onDestroy() {
        super.onDestroy()
    }
}
