package yair.rush

import kav.kavapp.api.Kav
import org.json.JSONObject
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Kav's own classes and functions that Rush works with, found once by name in Kav's code (Kav keeps its names in its
 * release builds). Kotlin's own classes are renamed in Kav's release (its Pair is "z3.g" in Kav 47), so a Pair is
 * found from Kav's own signatures, never by its name.
 *
 * If anything here isn't in this Kav, [ok] is false, the reason is in the extension's log, and Rush does nothing:
 * Kav works exactly as without it.
 */
internal class KavBits(private val kav: Kav) {
    private val loader = kav.kavClassLoader()
    private fun cls(name: String): Class<*> = Class.forName(name, true, loader)

    var ok = false; private set
    var why = ""; private set

    // Classes
    lateinit var legClass: Class<*>; private set
    lateinit var itineraryClass: Class<*>; private set
    private lateinit var planClass: Class<*>
    private lateinit var resolvedClass: Class<*>
    private lateinit var plannedClass: Class<*>
    private lateinit var settingsClass: Class<*>

    // KavPlan
    private lateinit var kavPlan: Any
    lateinit var planMethod: Method; private set
    private lateinit var replanAt: Method
    private lateinit var kavSettings: Method

    // Transit.Leg
    private lateinit var legKind: Method
    private lateinit var legTripId: Method
    private lateinit var legDep: Method
    private lateinit var legArr: Method
    private lateinit var legMeters: Method
    private lateinit var legTurns: Method
    private lateinit var legToStop: Method
    private lateinit var legShape: Method
    private lateinit var legOptions: Method
    private var legVisit: Method? = null
    private lateinit var legCopy: Method
    private var copyMask = 0

    // Transit.Itinerary
    private lateinit var itGuid: Method
    private lateinit var itLegs: Method
    private lateinit var itDep: Method
    private lateinit var itArr: Method
    private lateinit var itTags: Method
    private lateinit var itPolicy: Method
    private lateinit var itWith: Method

    // Transit.Plan and KavPlan.Planned
    private lateinit var planItineraries: Method
    private lateinit var planSections: Method
    private lateinit var planIsTaxiCard: Method
    private lateinit var planNew: Constructor<*>
    private lateinit var plannedPlan: Method
    private lateinit var plannedResolved: Method
    private lateinit var plannedErrors: Method
    private lateinit var plannedNew: Constructor<*>

    // Transit.Resolved
    private lateinit var resolvedGetters: List<Method>
    private lateinit var resolvedNew: Constructor<*>
    private lateinit var departures: Method
    private lateinit var alightAt: Method
    private lateinit var stopOf: Method
    private var stopPoint: Method? = null
    private var depTripId: Method? = null
    private var depTime: Method? = null
    private var depStatus: Method? = null

    // PlanSettings
    private lateinit var settingsNew: Constructor<*>
    private lateinit var settingsParts: List<Method>
    private lateinit var paceFast: Any
    private lateinit var spareTight: Any

    // Others
    private var extensions: Any? = null
    private var plannerAt: Method? = null
    private var pipeline: Any? = null
    private var routeJson: Method? = null
    private var switched: Method? = null
    private var stepAfterSwitch: Method? = null
    private var replanFromNew: Constructor<*>? = null
    private var bridge: Any? = null
    private var bridgeJourney: Method? = null
    private var bridgeEnd: Method? = null
    private var modelClass: Class<*>? = null
    private var journeyTrip: Method? = null
    private var journeyStopovers: Method? = null

    fun find() {
        try {
            legClass = cls("uk.noammm.kav.data.Transit\$Leg")
            itineraryClass = cls("uk.noammm.kav.data.Transit\$Itinerary")
            planClass = cls("uk.noammm.kav.data.Transit\$Plan")
            resolvedClass = cls("uk.noammm.kav.data.Transit\$Resolved")
            plannedClass = cls("uk.noammm.kav.data.KavPlan\$Planned")
            settingsClass = cls("uk.noammm.kav.data.PlanSettings")

            val kp = cls("uk.noammm.kav.data.KavPlan")
            kavPlan = kp.getField("INSTANCE").get(null)!!
            planMethod = kp.declaredMethods.first {
                it.name == "plan" && it.parameterTypes.size == 9 && !Modifier.isStatic(it.modifiers) &&
                    it.parameterTypes[2] == java.lang.Long.TYPE && it.parameterTypes[8] == settingsClass
            }
            replanAt = kp.getMethod("replanAt", itineraryClass)
            kavSettings = kp.getMethod("getSettings")

            legKind = legClass.getMethod("getKind")
            legTripId = legClass.getMethod("getTripId")
            legDep = legClass.getMethod("getDep")
            legArr = legClass.getMethod("getArr")
            legMeters = legClass.getMethod("getMeters")
            legTurns = legClass.getMethod("getTurns")
            legToStop = legClass.getMethod("getToStop")
            legShape = legClass.getMethod("getShape")
            legOptions = legClass.getMethod("getOptions")
            legVisit = runCatching { legClass.getMethod("getVisit") }.getOrNull()
            findLegCopy()

            itGuid = itineraryClass.getMethod("getGuid")
            itLegs = itineraryClass.getMethod("getLegs")
            itDep = itineraryClass.getMethod("getDep")
            itArr = itineraryClass.getMethod("getArr")
            itTags = itineraryClass.getMethod("getTags")
            itPolicy = itineraryClass.getMethod("getPolicy")
            itWith = cls("uk.noammm.kav.ext.PipelineKt").declaredMethods.first {
                it.name == "with" && it.parameterTypes.size == 6 && it.parameterTypes[0] == itineraryClass
            }

            planItineraries = planClass.getMethod("getItineraries")
            planSections = planClass.getMethod("getSections")
            planIsTaxiCard = planClass.getMethod("isTaxiCard", itineraryClass)
            planNew = planClass.getConstructor(List::class.java, List::class.java)
            plannedPlan = plannedClass.getMethod("getPlan")
            plannedResolved = plannedClass.getMethod("getResolved")
            plannedErrors = plannedClass.getMethod("getErrors")
            plannedNew = plannedClass.getConstructor(planClass, resolvedClass, List::class.java)

            // Resolved's eight parts, in its constructor's order.
            resolvedGetters = listOf("getLines", "getStops", "getRouteTypes", "getLive", "getShapes", "getPollSecs",
                "getPatterns", "getDelays").map { resolvedClass.getMethod(it) }
            resolvedNew = resolvedClass.constructors.first { c ->
                c.parameterTypes.size == 8 && c.parameterTypes[5] == Integer.TYPE
            }
            departures = resolvedClass.methods.first {
                it.name == "departures" && it.parameterTypes.size == 4 && it.parameterTypes[0] == legClass
            }
            alightAt = resolvedClass.getMethod("alightAt", legClass)
            stopOf = resolvedClass.getMethod("stop", Integer.TYPE)
            stopPoint = runCatching { stopOf.returnType.getMethod("getPoint") }.getOrNull()
            val dep = cls("uk.noammm.kav.data.Transit\$Departure")
            depTripId = dep.getMethod("getTripId")
            depTime = dep.getMethod("getTimeUtc")
            depStatus = runCatching { dep.getMethod("getStatus") }.getOrNull()

            settingsParts = listOf("getPace", "getWalking", "getChanges", "getSpare").map { settingsClass.getMethod(it) }
            settingsNew = settingsClass.constructors.first { it.parameterTypes.size == 4 }
            paceFast = enumValue(cls("uk.noammm.kav.data.PlanSettings\$Pace"), "FAST")
            spareTight = enumValue(cls("uk.noammm.kav.data.PlanSettings\$Spare"), "TIGHT")
            ok = true
        } catch (e: Throwable) {
            why = "${e.javaClass.simpleName}: ${e.message}"
            kav.log("Rush: this Kav doesn't have what Rush needs to search ($why). Rush is off.")
            return
        }
        // Extras: each one missing turns off only what needs it.
        try {
            val ext = cls("uk.noammm.kav.ext.Extensions")
            extensions = ext.getField("INSTANCE").get(null)
            plannerAt = ext.getMethod("plannerAt", String::class.java)
        } catch (e: Throwable) { kav.log("Rush: can't tell extension planners apart (${e.javaClass.simpleName})") }
        try {
            val p = cls("uk.noammm.kav.ext.Pipeline")
            pipeline = p.getField("INSTANCE").get(null)
            routeJson = p.methods.first { it.name == "routeJson" && it.parameterTypes.size == 2 }
        } catch (e: Throwable) { kav.log("Rush: can't read routes as text (${e.javaClass.simpleName})") }
        try {
            val r = cls("uk.noammm.kav.ui.ReplanKt")
            switched = r.declaredMethods.first { it.name == "switched" && it.parameterTypes.size == 5 }
            stepAfterSwitch = r.declaredMethods.first { it.name == "stepAfterSwitch" && it.parameterTypes.size == 3 }
            replanFromNew = cls("uk.noammm.kav.ui.ReplanFrom").declaredConstructors.first { it.parameterTypes.size == 4 }
                .apply { isAccessible = true }
            switched?.isAccessible = true
            stepAfterSwitch?.isAccessible = true
            val journey = cls("uk.noammm.kav.ActiveJourney")
            journeyTrip = journey.getMethod("getTrip")
            journeyStopovers = journey.getMethod("getStopovers")
            val b = cls("uk.noammm.kav.TripBridge")
            bridge = b.getField("INSTANCE").get(null)
            bridgeJourney = b.getMethod("getJourney")
            bridgeEnd = b.getMethod("getEnd")
            modelClass = cls("uk.noammm.kav.KavModel")
        } catch (e: Throwable) {
            switched = null
            kav.log("Rush: can't switch a trip under way in this Kav (${e.javaClass.simpleName})")
        }
    }

    private fun enumValue(c: Class<*>, name: String): Any = c.enumConstants!!.first { (it as Enum<*>).name == name }

    /**
     * Leg is a Kotlin data class: its copy$default takes every field, with a bit for each one to keep as it is. Only
     * its times change here. Its fields are checked against what Rush expects, so a Kav whose Leg changed shape turns
     * Rush off instead of making wrong legs.
     */
    private fun findLegCopy() {
        legCopy = legClass.declaredMethods.first {
            it.name == "copy\$default" && Modifier.isStatic(it.modifiers) && it.parameterTypes.size >= 8 &&
                it.parameterTypes[0] == legClass
        }
        val p = legCopy.parameterTypes
        val fields = p.size - 3 // the leg, then its fields, then the mask and a marker
        check(fields in 6..31) { "Leg has $fields fields" }
        check(p[1] == legKind.returnType && p[2] == Integer.TYPE && p[3] == java.lang.Long.TYPE &&
            p[4] == java.lang.Long.TYPE && p[5] == java.lang.Long.TYPE && p[p.size - 2] == Integer.TYPE) { "Leg's fields moved" }
        // Keep every field but dep (the 4th) and arr (the 5th).
        copyMask = ((1 shl fields) - 1) and (1 shl 3 or (1 shl 4)).inv()
    }

    // Reading

    fun kind(leg: Any) = (legKind.invoke(leg) as Enum<*>).name
    fun tripId(leg: Any) = legTripId.invoke(leg) as Long
    fun dep(leg: Any) = legDep.invoke(leg) as Long
    fun arr(leg: Any) = legArr.invoke(leg) as Long
    fun meters(leg: Any) = legMeters.invoke(leg) as Int
    fun turns(leg: Any) = (legTurns.invoke(leg) as List<*>).size
    fun toStop(leg: Any) = legToStop.invoke(leg) as Int
    fun shape(leg: Any) = legShape.invoke(leg) as List<*>
    fun options(leg: Any) = (legOptions.invoke(leg) as List<*>).filterNotNull()
    /** Whether a leg is the first on from a stop on the way ("Add a stop"): Kav marks it with a visit. Unknown: yes. */
    fun isVisit(leg: Any) = legVisit?.let { it.invoke(leg) != null } ?: true

    fun legs(it: Any) = (itLegs.invoke(it) as List<*>).filterNotNull()
    fun guid(it: Any) = itGuid.invoke(it) as String
    fun itDep(it: Any) = itDep.invoke(it) as Long
    fun itArr(it: Any) = itArr.invoke(it) as Long
    fun tags(it: Any) = (itTags.invoke(it) as List<*>).filterIsInstance<String>()

    fun plan(planned: Any): Any = plannedPlan.invoke(planned)!!
    fun resolved(planned: Any): Any = plannedResolved.invoke(planned)!!
    fun itineraries(planned: Any) = (planItineraries.invoke(plan(planned)) as List<*>).filterNotNull()
    fun isTaxiCard(planned: Any, it: Any) = planIsTaxiCard.invoke(plan(planned), it) as Boolean

    /** When a ride leaves its stop: its live time when Kav has one, else the plan's. Null when it's cancelled. */
    fun boardAt(resolved: Any, ride: Any, wait: Any?, now: Long): Long? {
        val list = (departures.invoke(resolved, ride, wait, now, null) as? List<*>).orEmpty()
        val id = tripId(ride)
        val own = list.firstOrNull { it != null && depTripId?.invoke(it) == id } ?: return dep(ride)
        if (depStatus?.invoke(own) == 3) return null
        return (depTime?.invoke(own) as? Long) ?: dep(ride)
    }

    /** When the ride gets to the rider's stop: its live time, or the plan moved by its delay. */
    fun alightAt(resolved: Any, ride: Any) = alightAt.invoke(resolved, ride) as Long

    /** Where a ride ends, as one of Kav's Pairs of latitude and longitude. */
    fun endPoint(resolved: Any, ride: Any): Any? {
        val stop = runCatching { stopOf.invoke(resolved, toStop(ride)) }.getOrNull()
        return stop?.let { s -> runCatching { stopPoint?.invoke(s) }.getOrNull() } ?: shape(ride).lastOrNull()
    }

    fun routeJson(it: Any, resolved: Any): JSONObject? =
        runCatching { routeJson?.invoke(pipeline, it, resolved) as? JSONObject }.getOrNull()

    fun riderSettings(): Any = kavSettings.invoke(kavPlan)!!

    /** Kav's own search, called by Rush itself (it goes through Rush's hook, which [Search] tells apart). */
    fun callPlan(vararg args: Any?): Any = try {
        planMethod.invoke(kavPlan, *args)!!
    } catch (e: java.lang.reflect.InvocationTargetException) {
        throw e.targetException
    }
    fun replanAt(trip: Any) = replanAt.invoke(kavPlan, trip) as String?
    fun isExtensionPlanner(at: String) = runCatching { plannerAt?.invoke(extensions, at) != null }.getOrDefault(false)

    // Pairs: Kotlin's, renamed in Kav's release. Read by their two fields.

    fun pairParts(p: Any): Pair<Any?, Any?> {
        val f = p.javaClass.declaredFields.filter { !Modifier.isStatic(it.modifiers) }.onEach { it.isAccessible = true }
        return f[0].get(p) to f[1].get(p)
    }

    fun latLon(p: Any): Pair<Double, Double>? {
        val (a, b) = pairParts(p)
        return if (a is Double && b is Double) a to b else null
    }

    // Making

    /** The same leg with new times. */
    fun legAt(leg: Any, dep: Long, arr: Long): Any {
        val p = legCopy.parameterTypes
        val args = arrayOfNulls<Any>(p.size)
        for (i in p.indices) args[i] = zero(p[i])
        args[0] = leg
        args[4] = dep
        args[5] = arr
        args[p.size - 2] = copyMask
        args[p.size - 1] = null
        return legCopy.invoke(null, *args)!!
    }

    /** The same route with new legs, leaving time and tags. */
    fun itineraryWith(it: Any, legs: List<Any>, dep: Long, tags: List<String>): Any =
        itWith.invoke(null, it, legs, dep, itArr(it), tags, itPolicy.invoke(it))!!

    /** A plan of these routes, with the sections and errors of [like], and the two Resolved put together. */
    fun planned(like: Any, routes: List<Any>, resolved: Any): Any {
        val plan = planNew.newInstance(routes, planSections.invoke(plan(like)))
        return plannedNew.newInstance(plan, resolved, plannedErrors.invoke(like))
    }

    /** Two Resolved as one: each map put together, the first's poll time. */
    @Suppress("UNCHECKED_CAST")
    fun merge(a: Any, b: Any): Any {
        val args = resolvedGetters.mapIndexed { i, g ->
            val x = g.invoke(a); val y = g.invoke(b)
            if (i == 5) x else (x as Map<Any?, Any?>) + (y as Map<Any?, Any?>)
        }
        return resolvedNew.newInstance(*args.toTypedArray())
    }

    /** The rider's planner settings with the fast walking pace and the tight time at the stop. */
    fun rushSettings(rider: Any): Any {
        val parts = settingsParts.map { it.invoke(rider) }.toMutableList()
        parts[0] = paceFast
        parts[3] = spareTight
        return settingsNew.newInstance(*parts.toTypedArray())
    }

    private fun zero(t: Class<*>): Any? = when (t) {
        java.lang.Long.TYPE -> 0L
        Integer.TYPE -> 0
        java.lang.Boolean.TYPE -> false
        java.lang.Double.TYPE -> 0.0
        java.lang.Float.TYPE -> 0f
        java.lang.Short.TYPE -> 0.toShort()
        java.lang.Byte.TYPE -> 0.toByte()
        Character.TYPE -> '\u0000'
        else -> null
    }

    // The trip under way, through the bridge Kav keeps for its trip service.

    val canSwitch get() = switched != null

    /** Kav's model, from the bridge's closure: the screen holding the trip under way. Null when Kav's screen is gone. */
    fun model(): Any? {
        val b = bridge ?: return null
        val mc = modelClass ?: return null
        for (getter in listOfNotNull(bridgeJourney, bridgeEnd)) {
            val lambda = runCatching { getter.invoke(b) }.getOrNull() ?: continue
            for (f in lambda.javaClass.declaredFields) {
                if (!mc.isAssignableFrom(f.type)) continue
                f.isAccessible = true
                return f.get(lambda)
            }
        }
        return null
    }

    fun journey(model: Any): Any? = model.javaClass.getMethod("getActiveJourney").invoke(model)

    /** The legs of the trip under way that come on from a stop on the way, by their place; empty when it's not [dep]/[arr]. */
    fun visitsOfTripUnderWay(dep: Long, arr: Long): Set<Int> {
        val trip = model()?.let { journey(it) }?.let { journeyTrip(it) } ?: return emptySet()
        if (itDep(trip) != dep || itArr(trip) != arr) return emptySet()
        return legs(trip).withIndex().filter { (_, l) -> isVisit(l) }.map { it.index }.toSet()
    }
    fun journeyTrip(journey: Any): Any = journeyTrip!!.invoke(journey)!!
    /** The stops on the way the trip under way was planned through, in order. */
    fun stopovers(journey: Any): List<Any> = (journeyStopovers!!.invoke(journey) as List<*>).filterNotNull()

    /**
     * The trip under way goes on with [route] from the end of its ride [rideIndex], as Kav's own New route from here
     * does it: the legs behind kept, the new route after them through the stops on the way still ahead ([stopsLeft]),
     * and the step-by-step guide on the same step.
     * On the main thread. The new trip's guid, or null when the trip isn't the one the route was found for.
     */
    fun switchTrip(
        model: Any, tripGuid: String, rideIndex: Int, at: Any, time: Long, route: Any, resolved: Any, stopsLeft: List<Any>,
    ): String? {
        val journey = journey(model) ?: return null
        if (guid(journeyTrip(journey)) != tripGuid) return null
        val from = replanFromNew!!.newInstance(at, time, rideIndex + 1, rideIndex + 1)
        // The stops on the way not passed yet, as Kav's own New route from here gives them.
        val next = switched!!.invoke(null, journey, from, route, resolved, stopsLeft)!!
        val step = model.javaClass.getMethod("getJourneyStep").invoke(model) as Int
        val nextStep = stepAfterSwitch!!.invoke(null, next, from, step) as Int
        // The step first, then the trip: the order Kav's own New route from here sets them in (ui/Navigate.kt).
        model.javaClass.getMethod("setJourneyStep", Integer.TYPE).invoke(model, nextStep)
        model.javaClass.getMethod("setActiveJourney", journey.javaClass).invoke(model, next)
        return guid(journeyTrip(next))
    }
}
