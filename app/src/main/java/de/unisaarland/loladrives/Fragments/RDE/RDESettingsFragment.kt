package de.unisaarland.loladrives.Fragments.RDE

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentTransaction
import com.warkiz.widget.IndicatorSeekBar
import com.warkiz.widget.OnSeekChangeListener
import com.warkiz.widget.SeekParams
import de.unisaarland.loladrives.MainActivity
import de.unisaarland.loladrives.OBDCommunication
import de.unisaarland.loladrives.R
import de.unisaarland.loladrives.Sources.GPSSource
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.android.synthetic.main.app_bar_main.*
import kotlinx.android.synthetic.main.fragment_r_d_e_settings.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.rdeapp.pcdftester.Sinks.RDEValidator
import pcdfEvent.EventType
import pcdfEvent.PCDFEvent
import pcdfEvent.events.GPSEvent
import pcdfEvent.events.obdEvents.OBDCommand
import pcdfEvent.events.obdEvents.OBDCommand.*

/**
 * A simple [Fragment] subclass.
 */
class RDESettingsFragment : Fragment() {
    private var distance = 83
    private var rdeValidator: RDEValidator? = null
    private lateinit var activity: MainActivity
    private var gpsInit = false
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.fragment_r_d_e_settings, container, false)
    }

    @ExperimentalCoroutinesApi
    override fun onStart() {
        super.onStart()
        activity = requireActivity() as MainActivity
        activity.title_textview.text = getString(R.string.rde_settings)
        activity.backButton.setImageResource(R.drawable.back_arrow_icon)
        textViewDistance.text = "$distance km"

        distanceSeekBar.onSeekChangeListener = object : OnSeekChangeListener {
            override fun onSeeking(seekParams: SeekParams) {
                distance = seekParams.progress
                textViewDistance.text = "$distance km"
            }

            override fun onStartTrackingTouch(seekBar: IndicatorSeekBar) {}
            override fun onStopTrackingTouch(seekBar: IndicatorSeekBar) {}
        }

        activity.checkConnection()
        if (!activity.bluetoothConnected) {
            val toast = Toast.makeText(
                context,
                "No Bluetooth OBD-Device Connected",
                Toast.LENGTH_LONG
            )
            toast.show()
            activity.progressBarBluetooth.visibility = View.INVISIBLE
            activity.onBackPressed()
        } else {
            if (!activity.tracking) {
                checkGPS()
                initRDE()

                startImageButton.setOnClickListener {
                    // Check whether the car
                    activity.rdeFragment.distance = distance.toDouble()

                    activity.supportFragmentManager.beginTransaction().replace(
                        R.id.frame_layout,
                        activity.rdeFragment
                    ).setTransition(FragmentTransaction.TRANSIT_FRAGMENT_OPEN).commit()
                }
            } else {
                val toast = Toast.makeText(
                    context,
                    "Live Monitoring is already ongoing",
                    Toast.LENGTH_LONG
                )
                toast.show()
                activity.progressBarBluetooth.visibility = View.INVISIBLE
                activity.onBackPressed()
            }
        }
        activity.progressBarBluetooth.visibility = View.INVISIBLE
    }

    @ExperimentalCoroutinesApi
    private fun initRDE() : Boolean {
        println("Starte Validtor und checke PIDS")
        rdeValidator = RDEValidator(
            activity.eventDistributor.registerReceiver(),
            activity
        )
        val rdeReady: Pair<OBDCommunication, List<OBDCommand>> = runBlocking {
            try {
                rdeValidator!!.performSupportedPidsCheck()
            } catch (e: Exception) {
                println("OBD Error: $e")
                Pair(OBDCommunication.OBD_COMMUNICATION_ERROR, listOf())
            }
        }

        fillCarInfo(rdeReady)
        if (rdeReady.first != OBDCommunication.OKAY) {
            return false
        }
        return true
    }
    private fun fillCarInfo(rdeReady: Pair<OBDCommunication, List<OBDCommand>>) {
        val result = rdeReady.first
        val suppList = rdeReady.second

        if (result == OBDCommunication.OBD_COMMUNICATION_ERROR) {
            val toast = Toast.makeText(
                context,
                getString(R.string.obd_comm_err),
                Toast.LENGTH_LONG
            )
            toast.show()
            activity.onBackPressed()
        }

        if (suppList.contains(SPEED)) {
            imageViewSpeed.setImageResource(R.drawable.bt_connected)
        } else {
            imageViewSpeed.setImageResource(R.drawable.bt_not_connected)
        }

        if (suppList.contains(AMBIENT_AIR_TEMPERATURE)) {
            imageViewTemp.setImageResource(R.drawable.bt_connected)
        } else {
            imageViewTemp.setImageResource(R.drawable.bt_not_connected)
        }

        if (suppList.intersect(listOf(NOX_SENSOR, NOX_SENSOR_CORRECTED, NOX_SENSOR_ALTERNATIVE, NOX_SENSOR_CORRECTED_ALTERNATIVE)).isEmpty()) {
            textViewNOXSensorInit.text = "No NOx Sensor found!"
            textViewNOXSensorInit.setTextColor(Color.RED)
        }

        var noxCalcString = "MAF"
        if (suppList.contains(ENGINE_FUEL_RATE) || suppList.contains(ENGINE_FUEL_RATE)) {
            noxCalcString += ", Fuel Rate"
        } else {
            if (suppList.contains(FUEL_AIR_EQUIVALENCE_RATIO)) {
                noxCalcString += ", FAE"
            }
        }
        textViewNoxCalc2.text = noxCalcString
    }

    private fun checkGPS() {
        GlobalScope.launch {
            val channel = Channel<PCDFEvent>(1000)
            val gpsSource = GPSSource(channel, activity)
            GlobalScope.launch(Dispatchers.Main) { gpsSource.start() }
            for (i in channel) {
                if (i is GPSEvent) {
                    println("Habe gps bekommen")
                    gpsInit = true
                    GlobalScope.launch(Dispatchers.Main) {
                        textViewGPSinit.text = "GPS da"
                    }
                    break
                }
            }
            GlobalScope.launch(Dispatchers.Main) { gpsSource.stop() }
            channel.close()
        }
    }
}
