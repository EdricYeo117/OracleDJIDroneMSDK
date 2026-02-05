package dji.sampleV5.aircraft.pages

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.activityViewModels
import dji.sampleV5.aircraft.databinding.FragRemoteAutomationBinding
import dji.sampleV5.aircraft.models.MediaVM
import dji.sampleV5.aircraft.remote.ui.RemoteControlPanelBinder

class RemoteAutomationFragment : DJIFragment() {

    private var binding: FragRemoteAutomationBinding? = null
    private val mediaVM: MediaVM by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragRemoteAutomationBinding.inflate(inflater, container, false)
        return binding!!.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Your frag_remote_automation.xml has: android:id="@+id/remotePanel"
        val panelRoot = binding!!.remotePanel.root
        RemoteControlPanelBinder(
            root = panelRoot,
            appContext = requireContext().applicationContext,
            mediaVM = mediaVM
        ).bind()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding = null
    }
}
