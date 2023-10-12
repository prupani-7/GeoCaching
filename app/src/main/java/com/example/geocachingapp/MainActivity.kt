package com.example.geocachingapp

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.databinding.DataBindingUtil
import androidx.lifecycle.lifecycleScope
import com.arcgismaps.ApiKey
import com.arcgismaps.ArcGISEnvironment
import com.arcgismaps.Color
import com.arcgismaps.data.Feature
import com.arcgismaps.data.ServiceFeatureTable
import com.arcgismaps.geometry.Envelope
import com.arcgismaps.geometry.GeodeticCurveType
import com.arcgismaps.geometry.GeodeticDistanceResult
import com.arcgismaps.geometry.Geometry
import com.arcgismaps.geometry.GeometryEngine
import com.arcgismaps.geometry.LinearUnit
import com.arcgismaps.geometry.LinearUnitId
import com.arcgismaps.geometry.Point
import com.arcgismaps.geometry.Polyline
import com.arcgismaps.geometry.SpatialReference
import com.arcgismaps.location.LocationDisplayAutoPanMode
import com.arcgismaps.mapping.ArcGISMap
import com.arcgismaps.mapping.BasemapStyle
import com.arcgismaps.mapping.Viewpoint
import com.arcgismaps.mapping.labeling.LabelStackAlignment
import com.arcgismaps.mapping.layers.FeatureLayer
import com.arcgismaps.mapping.symbology.FontWeight
import com.arcgismaps.mapping.symbology.HorizontalAlignment
import com.arcgismaps.mapping.symbology.MarkerSymbol
import com.arcgismaps.mapping.symbology.PictureMarkerSymbol
import com.arcgismaps.mapping.symbology.SimpleLineSymbol
import com.arcgismaps.mapping.symbology.SimpleLineSymbolMarkerStyle
import com.arcgismaps.mapping.symbology.SimpleLineSymbolStyle
import com.arcgismaps.mapping.symbology.SimpleMarkerSymbolStyle
import com.arcgismaps.mapping.symbology.TextSymbol
import com.arcgismaps.mapping.symbology.VerticalAlignment
import com.arcgismaps.mapping.view.Graphic
import com.arcgismaps.mapping.view.GraphicsOverlay
import com.arcgismaps.mapping.view.LocationDisplay
import com.arcgismaps.mapping.view.ScreenCoordinate
import com.example.geocachingapp.databinding.ActivityMainBinding
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch


class MainActivity : AppCompatActivity() {

    // set up data binding for the activity
    private val activityMainBinding: ActivityMainBinding by lazy {
        DataBindingUtil.setContentView(this, R.layout.activity_main)
    }

    private val mapView by lazy {
        activityMainBinding.mapView
    }

    private val geocacheFS =
        "https://services5.arcgis.com/N82JbI5EYtAkuUKU/arcgis/rest/services/geocaches_redlands/FeatureServer/0"

    // create a service feature table using the feature service URL
    private val serviceFeatureTable = ServiceFeatureTable(geocacheFS)

    // create a feature layer from the service feature table
    private val featureLayer = FeatureLayer.createWithFeatureTable(serviceFeatureTable)

    // create the graphic overlays
    private val graphicsOverlay: GraphicsOverlay by lazy { GraphicsOverlay() }

    // define a line symbol which will represent the river stream with its width defined.
    private val lineSymbol =
        SimpleLineSymbol(SimpleLineSymbolStyle.Dash, Color.fromRgba(23, 105, 187), 3f)

    private lateinit var polyline: Polyline

    private lateinit var distanceGeodetic: GeodeticDistanceResult

//    private val distanceInMiles: TextView by lazy {
//        activityMainBinding.distanceTV
//    }

    private val navigateButton: TextView by lazy {
        activityMainBinding.navigateButton
    }

    private val clearButton: TextView by lazy {
        activityMainBinding.clearButton
    }

    private lateinit var currentPosition: Point

    private var features: List<Feature>? = null

    private lateinit var screenCoordinate: ScreenCoordinate

    // set the arrow graphic for tapped location
    private val arrowSymbol by lazy {
        createArrowSymbol()
    }

    // set the default graphic for tapped location
    private val defaultSymbol by lazy {
        createDefaultSymbol()
    }

    private val recenterButton: MaterialButton by lazy {
        activityMainBinding.recenterButton
    }

    private lateinit var textView: TextView

    private val distanceTV by lazy {
        activityMainBinding.textView
    }

    private var textSymbol = TextSymbol()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // authentication with an API key or named user is
        // required to access basemaps and other location services
        ArcGISEnvironment.apiKey = ApiKey.create(BuildConfig.API_KEY)
        lifecycle.addObserver(mapView)
        // some parts of the API require an Android Context to properly interact with Android system
        // features, such as LocationProvider and application resources
        ArcGISEnvironment.applicationContext = applicationContext

        // add the feature layer to the maps operational layer
        val geocacheMap = ArcGISMap(BasemapStyle.ArcGISStreets).apply {
            operationalLayers.add(featureLayer)
        }

        // ****** This is the arrow symbol!  ******
        lineSymbol.markerStyle = SimpleLineSymbolMarkerStyle.Arrow

        // apply the map to the mapView
        mapView.apply {
            // set the map to be displayed in the layout's map view
            map = geocacheMap
            // create graphics overlays to show the inputs and results of the spatial operation
            graphicsOverlays.add(graphicsOverlay)
            // set an initial view point
            // Esri viewpoint
            // setViewpoint(Viewpoint(34.056, -117.194, 2000.0))
            setViewpoint(Viewpoint(34.053694, -117.222774, 4000.0))
        }

        // create a location display object
        var locationDisplay = mapView.locationDisplay
        locationDisplay.setAutoPanMode(LocationDisplayAutoPanMode.Navigation)

        // Start the location data source
        lifecycleScope.launch {
            locationDisplay.dataSource.start().getOrElse {
                showError("Error starting LocationDataSource: ${it.message} ")
                // check permissions to see if failure may be due to lack of permissions
                requestPermissions()
            }
        }

        lifecycleScope.launch {
            mapView.onSingleTapConfirmed.collect { tapEvent ->
                screenCoordinate = tapEvent.screenCoordinate
                getSelectedFeatureLayer(screenCoordinate)?.forEach { feature ->
                    // create a textview for the callout
                    textView = TextView(applicationContext).apply {
                        text = feature.attributes["Name"].toString() + " Tree"
                    }
                    // give any item selected on the mapView a green selection halo
                    mapView.selectionProperties.color = Color.green

                    // display the callout in the map view
                    mapView.callout.show(textView, feature, tapEvent.mapPoint)
                }
            }
        }

        // Solution 2 - comment when trying Solution 1 below
        lifecycleScope.launch {
            // when phone is rotates, headingChanged event will set the auto pan mode to NAVIGATION.
            locationDisplay.dataSource.headingChanged.collect {
                locationDisplay.setAutoPanMode(LocationDisplayAutoPanMode.Navigation)
            }
        }

        lifecycleScope.launch {
            locationDisplay.dataSource.locationChanged.collect { it ->
                locationDisplay.autoPanMode.value
                // get the current location of the user
                currentPosition = it.position

                // project the WGS84 point to Web mercator point
                val currentPositionwgs84 =
                    GeometryEngine.projectOrNull(
                        currentPosition,
                        SpatialReference.webMercator()
                    )

                if (features != null && clearButton.isEnabled) {
                    val featureLocation =
                        features!![0].geometry?.let { extractMapLocation(it) }

                    // create a polyline connecting the 2 points above
                    polyline = Polyline(listOf(currentPositionwgs84!!, featureLocation!!))
                    graphicsOverlay.graphics.clear()

                    distanceGeodetic = GeometryEngine.distanceGeodeticOrNull(
                        currentPositionwgs84,
                        featureLocation,
                        LinearUnit(LinearUnitId.Meters),
                        null,
                        GeodeticCurveType.NormalSection
                    )!!

                    // display distance to the destination point
                    var distance = distanceGeodetic.distance
                    distanceTV.text = "Distance to the Destination: ${distance.toInt()} m"
                    textSymbol.apply {
                        color = Color.black
                        text = " ${distance.toInt()} m"
                        size = 14f
                        fontWeight = FontWeight.Bold
                        horizontalAlignment = HorizontalAlignment.Left
                        verticalAlignment = VerticalAlignment.Bottom
                    }
                    val labelPoint = polyline.extent.center
                    val labelGraphic = Graphic(labelPoint, textSymbol)
                    // create a Graphic using the polyline geometry and the lineSymbol and add it to the GraphicsOverlay
                    graphicsOverlay.graphics.addAll(listOf(Graphic(polyline, lineSymbol), labelGraphic))
                    val currentPositionbuffer = GeometryEngine.bufferOrNull(currentPositionwgs84, 10.0)
                    val featureLocationbuffer = GeometryEngine.bufferOrNull(featureLocation, 10.0)
                    val unionGeometry = GeometryEngine.union(currentPositionbuffer!!, featureLocationbuffer!!)
                    val extent = unionGeometry?.extent
                    // This code was added to dynamically zoom in to the mapview as the user approaches the destination point. )
                    mapView.setViewpoint(Viewpoint(extent!!)) // Note: this line will turn the auto pan mode OFF
                    // Solution 2 to pan the map when the device moves
                    // mapView.setViewpoint(Viewpoint(extent!!, it.course))
                }
            }
        }

        // navigate to the destination point
        navigateButton.setOnClickListener {
            // disable button
            navigateButton.isEnabled = false
            clearButton.isEnabled = true

        }

        // navigate to the destination point
        clearButton.setOnClickListener {
            // disable button
            navigateButton.isEnabled = false
            clearButton.isEnabled = false
            graphicsOverlay.graphics.clear()
            graphicsOverlay.clearSelection()
            featureLayer.clearSelection()
            distanceTV.text = ""
            textSymbol = TextSymbol()
            locationDisplay.defaultSymbol = defaultSymbol
        }

        // wire up recenter button
        recenterButton.setOnClickListener {
            mapView.locationDisplay.setAutoPanMode(LocationDisplayAutoPanMode.Navigation)
            recenterButton.isEnabled = false
        }

        // listen if user navigates the map view away from the
        // location display, activate the recenter button
        lifecycleScope.launch {
            locationDisplay.autoPanMode.filter { it == LocationDisplayAutoPanMode.Off }
                .collect { recenterButton.isEnabled = true }
        }
    }

    /**
     * Displays the number of features selected on the given [screenCoordinate]
     */
    private suspend fun getSelectedFeatureLayer(screenCoordinate: ScreenCoordinate): List<Feature>? {

        navigateButton.isEnabled = true
        // clear the previous selection
        featureLayer.clearSelection()
        // set a tolerance for accuracy of returned selections from point tapped
        val tolerance = 25.0
        // create a IdentifyLayerResult using the screen coordinate
        val identifyLayerResult =
            mapView.identifyLayer(featureLayer, screenCoordinate, tolerance, false, -1)
        // handle the result's onSuccess and onFailure
        identifyLayerResult.apply {
            onSuccess { identifyLayerResult ->
                // get the elements in the selection that are features
                features = identifyLayerResult.geoElements.filterIsInstance<Feature>()
                // add the features to the current feature layer selection
                featureLayer.selectFeatures(features!!)
            }
            onFailure {
                val errorMessage = "Select feature failed: " + it.message
                Log.e(localClassName, errorMessage)
                Snackbar.make(mapView, errorMessage, Snackbar.LENGTH_SHORT).show()
            }
        }
        return features
    }

    private fun extractMapLocation(geometry: Geometry): Point {
        val mapViewSpatialReference = mapView.spatialReference.value
        val featureGeometry =
            GeometryEngine.projectOrNull(geometry, mapViewSpatialReference!!) as Point
        return featureGeometry
    }

    /**
     * Request fine and coarse location permissions for API level 23+.
     */
    private fun requestPermissions() {
        // coarse location permission
        val permissionCheckCoarseLocation =
            ContextCompat.checkSelfPermission(
                this@MainActivity,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) ==
                    PackageManager.PERMISSION_GRANTED
        // fine location permission
        val permissionCheckFineLocation =
            ContextCompat.checkSelfPermission(
                this@MainActivity,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) ==
                    PackageManager.PERMISSION_GRANTED

        // if permissions are not already granted, request permission from the user
        if (!(permissionCheckCoarseLocation && permissionCheckFineLocation)) {
            ActivityCompat.requestPermissions(
                this@MainActivity,
                arrayOf(
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ),
                2
            )
        } else {
            // permission already granted, so start the location display
            lifecycleScope.launch {
                mapView.locationDisplay.dataSource.start()
            }
        }
    }

    /**
     * Handle the permissions request response.
     */
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            lifecycleScope.launch {
                mapView.locationDisplay.dataSource.start()
            }
        } else {
            Snackbar.make(
                mapView,
                "Location permissions required to run this sample!",
                Snackbar.LENGTH_LONG
            ).show()
        }
    }

    private fun showError(message: String) {
        Log.e(localClassName, message)
        Snackbar.make(mapView, message, Snackbar.LENGTH_SHORT).show()
    }

    /**
     * Create a picture marker symbol to represent a pin at the tapped location
     */
    private fun createArrowSymbol(): PictureMarkerSymbol {
        // get pin drawable
        val arrowDrawable = ContextCompat.getDrawable(
            this,
            R.drawable.locationdisplaynavigationicon
        )
        //add a graphic for the tapped point
        val arrowSymbol = PictureMarkerSymbol.createWithImage(
            arrowDrawable as BitmapDrawable
        )
        arrowSymbol.apply {
            // resize the dimensions of the symbol
            width = 20f
            height = 20f
        }
        return arrowSymbol
    }

    /**
     * Create a picture marker symbol to represent a pin at the tapped location
     */
    private fun createDefaultSymbol(): PictureMarkerSymbol {
        // get pin drawable
        val defaultDrawable = ContextCompat.getDrawable(
            this,
            R.drawable.locationdisplaydefaulticon
        )
        //add a graphic for the tapped point
        val defaultSymbol = PictureMarkerSymbol.createWithImage(
            defaultDrawable as BitmapDrawable
        )
        defaultSymbol.apply {
            // resize the dimensions of the symbol
            width = 20f
            height = 20f
        }
        return defaultSymbol
    }
}

