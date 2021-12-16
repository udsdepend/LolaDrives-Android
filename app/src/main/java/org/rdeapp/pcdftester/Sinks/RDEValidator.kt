package org.rdeapp.pcdftester.Sinks

import android.util.Log
import de.unisaarland.loladrives.Constants
import de.unisaarland.loladrives.Constants.Companion.RDE_RTLOLA_ENGINE
import de.unisaarland.loladrives.Constants.Companion.RDE_RTLOLA_INPUT_QUANTITIES
import de.unisaarland.loladrives.Constants.Companion.RDE_RTLOLA_INPUT_QUANTITIES.*
import de.unisaarland.loladrives.MainActivity
import de.unisaarland.loladrives.OBDCommunication
import de.unisaarland.loladrives.OBDCommunication.*
import de.unisaarland.loladrives.R
import de.unisaarland.loladrives.Sources.OBDSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.BroadcastChannel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.runBlocking
import pcdfEvent.EventType
import pcdfEvent.EventType.GPS
import pcdfEvent.EventType.OBD_RESPONSE
import pcdfEvent.PCDFEvent
import pcdfEvent.events.GPSEvent
import pcdfEvent.events.obdEvents.OBDCommand
import pcdfEvent.events.obdEvents.OBDCommand.*
import pcdfEvent.events.obdEvents.OBDEvent
import pcdfEvent.events.obdEvents.obdIntermediateEvents.OBDIntermediateEvent
import pcdfEvent.events.obdEvents.obdIntermediateEvents.multiComponentEvents.MAFSensorEvent
import pcdfEvent.events.obdEvents.obdIntermediateEvents.reducedComponentEvents.FuelRateReducedEvent
import pcdfEvent.events.obdEvents.obdIntermediateEvents.reducedComponentEvents.NOXReducedEvent
import pcdfEvent.events.obdEvents.obdIntermediateEvents.singleComponentEvents.*
import java.io.File

const val VERBOSITY_MODE = false
const val EXTENDED_LOGGING = false

/**
 * Main class for the RTLola communication and validation of the RDE constraints.
 * Receives PCDFEvents from the PCDFCore (OBD, GPS) and forwards them to the RTLola engine.
 * Sends RTLola results to the [RDEUIUpdater].
 *
 * @property inputChannel Channel over which we receive the [PCDFEvent]s to be sent to the RTLola engine.
 * @property activity Current MainActivity
 */
class RDEValidator(
    private val inputChannel: ReceiveChannel<PCDFEvent>?,
    val activity: MainActivity
) {
    var isPaused = false

    // Last event time in seconds.
    private var time: Double = 0.0

    // The sensor profile of the car which is determined.
    var rdeProfile: MutableList<OBDCommand> = mutableListOf()
    var extendedLoggingProfile: MutableList<OBDCommand> = if (EXTENDED_LOGGING) {
        mutableListOf(RPM)
    } else {
        mutableListOf()
    }
    private var fuelType = "Diesel"
    private var fuelRateSupported = true
    private var faeSupported = true

    private val specBody: String
    private val specHeader: String
    private val specFuelRateInput: String
    private val specFuelRateToCo2Diesel: String
    private val specFuelRateToEMFDiesel: String
    private val specFuelRateToCo2Gasoline: String
    private val specFuelRateToEMFGasoline: String
    private val specMAFToFuelRateDieselFAE: String
    private val specMAFToFuelRateDiesel: String
    private val specMAFToFuelRateGasolineFAE: String
    private val specMAFToFuelRateGasoline: String

    @ExperimentalCoroutinesApi
    val outputChannel = BroadcastChannel<DoubleArray>(10000)

    // Second OBDSource used for determination of the sensor profile.
    private val source = OBDSource(
        activity.mBluetoothSocket?.inputStream,
        activity.mBluetoothSocket?.outputStream,
        Channel(10000),
        mutableListOf(),
        activity.mUUID
    )

    // Latest relevant values from OBD- and GPSSource.
    private var inputs: MutableMap<RDE_RTLOLA_INPUT_QUANTITIES, Double?> = mutableMapOf(
        VELOCITY to null,
        ALTITUDE to null,
        TEMPERATURE to null,
        NOX_PPM to 50.0,
        MASS_AIR_FLOW to null,
        FUEL_RATE to null,
        FUEL_AIR_EQUIVALENCE to null
    )

    /*
        Initial data is complete if we received values for all the sensors in the determined sensor profile and GPS data.
        If complete, we can start communicating with the RTLola engine.
     */
    private val initialDataComplete: Boolean
        get() {
            var countAvailable = 0
            for (pair in inputs) {
                if (pair.value != null) {
                    countAvailable++
                }
            }
            return countAvailable == rdeProfile.size + 2
        }

    // Load the FFI RTLola engine.
    init {
        System.loadLibrary("rtlola_kotlin_bridge")
        specBody = activity.resources?.openRawResource(R.raw.spec_body)?.bufferedReader().use {
            it?.readText() ?: ""
        }
        specHeader = activity.resources?.openRawResource(R.raw.spec_header)?.bufferedReader().use {
            it?.readText() ?: ""
        }
        specFuelRateInput = activity.resources?.openRawResource(R.raw.spec_fuel_rate_input)?.bufferedReader().use {
            it?.readText() ?: ""
        }
        specFuelRateToCo2Diesel =
            activity.resources?.openRawResource(R.raw.spec_fuel_rate_to_co2_diesel)?.bufferedReader().use {
                it?.readText() ?: ""
            }
        specFuelRateToEMFDiesel =
            activity.resources?.openRawResource(R.raw.spec_fuel_rate_to_emf_diesel)?.bufferedReader().use {
                it?.readText() ?: ""
            }
        specFuelRateToCo2Gasoline =
            activity.resources?.openRawResource(R.raw.spec_fuelrate_to_co2_gasoline)?.bufferedReader().use {
                it?.readText() ?: ""
            }
        specFuelRateToEMFGasoline =
            activity.resources?.openRawResource(R.raw.spec_fuelrate_to_emf_gasoline)?.bufferedReader().use {
                it?.readText() ?: ""
            }
        specMAFToFuelRateDieselFAE =
            activity.resources?.openRawResource(R.raw.spec_maf_to_fuel_rate_diesel_fae)?.bufferedReader().use {
                it?.readText() ?: ""
            }
        specMAFToFuelRateDiesel =
            activity.resources?.openRawResource(R.raw.spec_maf_to_fuel_rate_diesel)?.bufferedReader().use {
                it?.readText() ?: ""
            }
        specMAFToFuelRateGasolineFAE =
            activity.resources?.openRawResource(R.raw.spec_maf_to_fuel_rate_gasoline_fae)?.bufferedReader().use {
                it?.readText() ?: ""
            }
        specMAFToFuelRateGasoline =
            activity.resources?.openRawResource(R.raw.spec_maf_to_fuel_rate_gasoline)?.bufferedReader().use {
                it?.readText() ?: ""
            }

    }

    fun test() {
        val monitor = initmonitor(
                buildSpec(),
                "a,va,weight,u_va_pct,u_rpa,r_rpa"
        )
        println("Rust Feedback: " + monitor)
        println(sendevent(doubleArrayOf(10.0,10.0,10.0,10.0,10.0,10.0,1.0)).joinToString())
        println(sendevent(doubleArrayOf(20.0,10.0,10.0,10.0,10.0,10.0,2.1)).joinToString())
        println(sendevent(doubleArrayOf(25.0,10.0,10.0,10.0,10.0,10.0,3.3)).joinToString())
        println(sendevent(doubleArrayOf(35.0,10.0,10.0,10.0,10.0,10.0,4.3)).joinToString())
        println(sendevent(doubleArrayOf(45.0,10.0,10.0,10.0,10.0,10.0,5.3)).joinToString())
        println(sendevent(doubleArrayOf(55.0,10.0,10.0,10.0,10.0,10.0,6.3)).joinToString())

    }

    /**
     * Initializes the RTLola monitor with a given specification.
     *
     * @param spec RTLola specification as a string.
     * @return String "worked" if initialization went fine, or some error description if something went wrong.
     */
    private external fun initmonitor(spec: String, relevant_outsputs: String): String

    /**
     * Sends an array of update values to the RTLola engine to extend the InputStreams.
     * The relevant RTLola OutputStreams are periodic streams (sampled at 1Hz), therefore the returned array of relevant
     * OutputStream-Values may be empty,if the sent event did not make the streams evaluate, or could contain a lot more
     * than one value per OutputStream (the events are ordered linearly), if the sent event makes a leap in time (e.g.
     * after a connection loss).
     *
     * @param inputs Array of extension values for the RTLola InputStreams, in the same order they are defined in the
     * given RTLola specification.
     * @return Array of relevant outputs, if there are new events.
     */
    private external fun sendevent(inputs: DoubleArray): DoubleArray

    /**
     * Build the specification depending on the determined sensor profile and initialize the RTLola monitor.
     */
    fun initSpec() {
        initmonitor(
            buildSpec(),
                "a"
        )
    }

    @ExperimentalCoroutinesApi
    suspend fun startRDETrack() {
        for (event in inputChannel!!) {
            println(inputs.values.joinToString())
            collectData(event)
        }
    }

    /**
     * Main function for handling an received PCDFEvent and communicating with the RTLola engine.
     * Updates the [inputs], adds the current time (highest received timestamp) and sends inputs to the RTLola monitor.
     */
    @ExperimentalCoroutinesApi
    private fun collectData(event: PCDFEvent): DoubleArray {
        if (event.type == GPS) {
            inputs[ALTITUDE] = (event as GPSEvent).altitude
        } else if (event.type == OBD_RESPONSE) {
            if (EXTENDED_LOGGING) {
                val e = (event as OBDEvent).toIntermediate()
                val cmd = OBDCommand.getCommand(e.mode, e.pid)
                if (cmd !in rdeProfile) {
                    return doubleArrayOf()
                }
            }
            // Reduces the event if possible (e.g. NOx or FuelRate events) using the PCDFCore library.
            val rEvent = activity.sensorReducer.reduce(
                (event as OBDEvent).toIntermediate()
            )
            collectOBDEvent((rEvent as OBDIntermediateEvent))
        }

        // Check whether we have received data for every input needed and that we are not paused (bluetooth disconnected).
        if (initialDataComplete && !isPaused) {
            val inputsToSend = mutableListOf<Double>()

            for (input in this.inputs.values) {
                if (input != null) {
                    inputsToSend.add(input)
                }
            }
            // Prevent time from going backwards
            time = maxOf(time, event.timestamp.toDouble() / 1_000_000_000.0)
            inputsToSend.add(time)
            val inputsArray = inputsToSend.toDoubleArray()

            if (VERBOSITY_MODE) {
                println("Sending(Lola): ${inputsArray.joinToString()}")
            }

            // Send latest received inputs to the RTLola monitor to update our streams, in return we receive an array of
            // values of selected OutputStreams (see: lola-rust-bridge) which we send to the outputchannel (e.g. the UI).
            val lolaResult = sendevent(inputsArray)
            if (VERBOSITY_MODE) {
                println("Receiving(Lola): ${lolaResult.joinToString()}")
            }
            // The result may be empty, since we are referring to periodic streams (1 Hz). So we receive updated results
            // every full second.
            if (lolaResult.isNotEmpty()) {
                outputChannel.offer(lolaResult)
            }
            return lolaResult
        }

        return doubleArrayOf()
    }

    /**
     * Checks an received OBDEvent for relevance for our RDE track and updates the input values for the RTLola engine
     * accordingly.
     * @param event [PCDFEvent] to be collected.
     */
    private fun collectOBDEvent(event: OBDIntermediateEvent) {
        when (event) {
            is SpeedEvent -> {
                inputs[VELOCITY] = event.speed.toDouble()
            }
            is AmbientAirTemperatureEvent -> {
                inputs[TEMPERATURE] = event.temperature.toDouble() + 273.15 // C -> K
            }
            is MAFAirFlowRateEvent -> {
                inputs[MASS_AIR_FLOW] = event.rate
            }
            is MAFSensorEvent -> {
                inputs[MASS_AIR_FLOW] = event.mafSensorA // TODO: add reduction for this one
            }
            is NOXReducedEvent -> {
                inputs[NOX_PPM] = event.nox_ppm.toDouble()
            }
            is FuelRateReducedEvent -> {
                inputs[FUEL_RATE] = event.fuelRate
            }
            is FuelAirEquivalenceRatioEvent -> {
                inputs[FUEL_AIR_EQUIVALENCE] = event.ratio
            }
        }
    }

    @ExperimentalCoroutinesApi
    suspend fun getSupportedPids(): List<Int> {
        return source.getSupportedPids(1)
    }

    @ExperimentalCoroutinesApi
    suspend fun getFuelType(): String {
        return source.getFuelType()!!.fueltype
    }

    /**
     * Checks the connected car's supported sensor profile for RDE test sufficiency and determines which available sensors
     * should be used.
     * Generates the [rdeProfile] of PIDs to be used.
     *
     * @param supportedPids The list of the car's supported PIDs, from [getSupportedPids].
     * @param fuelType The car's fuel type, from [getFuelType].
     * @return If an RDE test is possible with the connected car.
     */
    private fun checkSupportedPids(supportedPids: List<Int>, fuelType: String): Pair<Boolean, List<OBDCommand>> {
        // If the car is not a diesel or gasoline, the RDE test is not possible since there are no corresponding
        // specifications.
        val commandList = mutableListOf<OBDCommand>()
        var validPids = true
        if (fuelType != "Diesel" && fuelType != "Gasoline") {
            println("Incompatible for RDE: Fuel type unknown or invalid ('${fuelType}')")
            validPids = false
        }

        // Velocity information to determine acceleration, distance travelled and to calculate the driving dynamics.
        if (supportedPids.contains(0x0D)) {
            rdeProfile.add(SPEED)
            commandList.add(SPEED)
        } else {
            println("Incompatible for RDE: Speed data not provided by the car.")
            validPids = false
        }

        // Ambient air temperature for checking compliance with the environmental constraints.
        if (supportedPids.contains(0x46)) {
            rdeProfile.add(AMBIENT_AIR_TEMPERATURE)
            commandList.add(AMBIENT_AIR_TEMPERATURE)
        } else {
            println("Incompatible for RDE: Ambient air temperature not provided by the car.")
            validPids = false
        }

        // NOx sensor(s) to check for violation of the EU regulations.
        var noxFound = false
        if (supportedPids.contains(0x83)) {
            rdeProfile.add(NOX_SENSOR)
            commandList.add(NOX_SENSOR)
            noxFound = true
        }

        if (supportedPids.contains(0xA1)) {
            if (!noxFound) rdeProfile.add(NOX_SENSOR_CORRECTED)
            commandList.add(NOX_SENSOR_CORRECTED)
            noxFound = true
        }

        if (supportedPids.contains(0xA7)) {
            if (!noxFound) rdeProfile.add(NOX_SENSOR_ALTERNATIVE)
            commandList.add(NOX_SENSOR_ALTERNATIVE)
            noxFound = true
        }

        if (supportedPids.contains(0xA8)) {
            if (!noxFound) rdeProfile.add(NOX_SENSOR_CORRECTED_ALTERNATIVE)
            commandList.add(NOX_SENSOR_CORRECTED_ALTERNATIVE)
            noxFound = true
        }

        if (!noxFound) {
            commandList.add(NOX_SENSOR)
            validPids = true //TODO: change
        }

        // Fuelrate sensors for calculation of the exhaust mass flow. Can be replaced through MAF.
        fuelRateSupported = when {
            supportedPids.contains(0x5E) -> {
                rdeProfile.add(ENGINE_FUEL_RATE)
                commandList.add(ENGINE_FUEL_RATE)
                true
            }
            supportedPids.contains(0x9D) -> {
                rdeProfile.add(ENGINE_FUEL_RATE_MULTI)
                commandList.add(ENGINE_FUEL_RATE_MULTI)
                true
            } else -> {
                println("RDE: Fuel rate not provided by the car.")
                false
            }
        }

        // Mass air flow rate for the calcuation of the exhaust mass flow.
        when {
            supportedPids.contains(0x10) -> {
                rdeProfile.add(MAF_AIR_FLOW_RATE)
                commandList.add(MAF_AIR_FLOW_RATE)
            }
            supportedPids.contains(0x66) -> {
                rdeProfile.add(MAF_AIR_FLOW_RATE_SENSOR)
                commandList.add(MAF_AIR_FLOW_RATE_SENSOR)
            } else -> {
                println("Incompatible for RDE: Mass air flow not provided by the car.")
                validPids = false
            }
        }

        // Fuel air equivalence ratio for a more precise calculation of the fuel rate with MAF.
        faeSupported = if (supportedPids.contains(0x44) && !fuelRateSupported) {
            rdeProfile.add(FUEL_AIR_EQUIVALENCE_RATIO)
            commandList.add(FUEL_AIR_EQUIVALENCE_RATIO)
            true
        } else {
            println("RDE: Fuel air equivalence ratio not provided by the car.")
            false
        }

        println("Car compatible for RDE tests.")
        println("Command list: "+ commandList.joinToString())
        return Pair(validPids,commandList)
    }

    /**
     * Performs an Supported PIDs request to the car.
     * Checks whether mandatory PIDs are supported by the connected car and an RDE Test is possible.
     *
     * @return If a RDE Test is possible.
     */
    @ExperimentalCoroutinesApi
    suspend fun performSupportedPidsCheck(): Pair<OBDCommunication,List<OBDCommand>> {
        source.initELM()
        if (!source.initProtocol()) {
            return Pair(UNSUPPORTED_PROTOCOL, listOf())
        }
        val supportedPids = getSupportedPids()
        // Check the Cars Fuel Type
        fuelType = if (supportedPids.contains(0x51)) { getFuelType() } else { return Pair(NO_FUELTYPE, listOf()) }
        val check = checkSupportedPids(supportedPids, fuelType)
        return if (check.first) { Pair(OKAY,check.second) } else { Pair(INSUFFICIENT_SENSORS,check.second) }
    }

    /**
     * Offline Monitoring of prior RDE tests (attempts).
     * @param data The events of the RDE trip to be offline monitored.
     * @return The last RTLola results, containing the final values for the selected OutputStreams.
     * @throws IllegalStateException If the Input-Events do not form a valid RDE-Drive attempt made with LolaDrives.
     *
     */
    @ExperimentalCoroutinesApi
    fun monitorOffline(dataIterator: Iterator<PCDFEvent>): DoubleArray {
        if (!dataIterator.hasNext()) {
            throw IllegalStateException()
        }

        val initialEvents = mutableListOf<PCDFEvent>()
        // Get initial events
        while (dataIterator.hasNext()) {
            val event = dataIterator.next()
            if (event.toIntermediate().type == OBD_RESPONSE) {
                val ievent = event.toIntermediate() as OBDIntermediateEvent
                val command = OBDCommand.getCommand(ievent.mode, ievent.pid)
                if (command !in Constants.NOT_TRACKABLE_EVENTS && command !in Constants.SINGLE_TIME_EVENTS) {
                    break
                }
            }
            initialEvents.add(event)
        }


        // Check initial events for supported PIDs, fuel type, etc.
        val suppPids = mutableListOf<Int>()
        for (event in initialEvents) {
            if (event.type == OBD_RESPONSE) {
                // Get Supported PIDs
                when (val iEvent = (event as OBDEvent).toIntermediate()) {
                    is SupportedPidsEvent -> {
                        suppPids.addAll(iEvent.supportedPids)
                    }
                    // Get Fueltype
                    is FuelTypeEvent -> {
                        fuelType = iEvent.fueltype
                    }
                }
            }
        }

        if (suppPids.isEmpty() || fuelType.isBlank()) {
            throw IllegalStateException()
        }

        // Check Supported PIDs
        val supported = checkSupportedPids(suppPids, fuelType).first
        if (!supported) {
            throw IllegalStateException()
        }

        // Setup RTLola Monitor
        initmonitor(
            buildSpec(),
                "a"
        )

        var result = doubleArrayOf()

        // Collect events, similar to online monitoring.
        while (dataIterator.hasNext()) {
            val event = dataIterator.next()
            val lolaResult = runBlocking { collectData(event) }
            result = if (lolaResult.isNotEmpty()) { lolaResult } else { result }
        }
        return result
    }

    /**
     * Builds the RTLola specification to be used for the RDE test, depending on the car's
     * determined sensor profile from [checkSupportedPids].
     * @return The RTLola specification for initialization of the RTLola monitor.
     */
    private fun buildSpec(): String {
        val s = StringBuilder()
        s.append(specHeader)

        if (fuelRateSupported) {
            s.append(specFuelRateInput)
        } else {
            when (fuelType) {
                "Diesel" -> {
                    if (faeSupported) {
                        s.append(specMAFToFuelRateDieselFAE)
                    } else {
                        s.append(specMAFToFuelRateDiesel)
                    }
                }
                "Gasoline" -> {
                    if (faeSupported) {
                        s.append(specMAFToFuelRateGasolineFAE)
                    } else {
                        s.append(specMAFToFuelRateGasoline)
                    }
                }
            }
        }
        when (fuelType) {
            "Diesel" -> {
                s.append(specFuelRateToCo2Diesel)
                s.append(specFuelRateToEMFDiesel)
            }
            "Gasoline" -> {
                s.append(specFuelRateToCo2Gasoline)
                s.append(specFuelRateToEMFGasoline)
            }
        }
        s.append(specBody)
        return s.toString()
    }
}
