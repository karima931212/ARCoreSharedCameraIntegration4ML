package pat.narendra.zhealthmeasure.ui.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProviders
import androidx.navigation.fragment.findNavController
import pat.narendra.zhealthmeasure.R

class HomeFragment : Fragment() {

  private lateinit var homeViewModel: HomeViewModel

  override fun onCreateView(
    inflater: LayoutInflater,
    container: ViewGroup?,
    savedInstanceState: Bundle?
  ): View? {
    val root = inflater.inflate(R.layout.fragment_home, container, false)
    homeViewModel =
    ViewModelProviders.of(this).get(HomeViewModel::class.java)

    val textView: TextView = root.findViewById(R.id.text_home)
    homeViewModel.text.observe(viewLifecycleOwner, Observer {
      textView.text = it
    })
    root.findViewById<Button>(R.id.ARbutton).setOnClickListener{
      findNavController().navigate(R.id.action_nav_home_to_nav_sharedCamera)
    }
    return root
  }
}