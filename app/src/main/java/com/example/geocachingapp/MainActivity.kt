package com.example.geocachingapp

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
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
import com.arcgismaps.geometry.Geometry
import com.arcgismaps.geometry.GeometryEngine
import com.arcgismaps.geometry.Point
import com.arcgismaps.geometry.Polyline
import com.arcgismaps.geometry.SpatialReference
import com.arcgismaps.location.LocationDisplayAutoPanMode
import com.arcgismaps.mapping.ArcGISMap
import com.arcgismaps.mapping.BasemapStyle
import com.arcgismaps.mapping.Viewpoint
import com.arcgismaps.mapping.layers.FeatureLayer
import com.arcgismaps.mapping.symbology.SimpleLineSymbol
import com.arcgismaps.mapping.symbology.SimpleLineSymbolStyle
import com.arcgismaps.mapping.view.Graphic
import com.arcgismaps.mapping.view.GraphicsOverlay
import com.arcgismaps.mapping.view.ScreenCoordinate
import com.example.geocachingapp.databinding.ActivityMainBinding
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private val TAG = MainActivity::class.java.simpleName

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

    private lateinit var screenCoordinate: ScreenCoordinate

    // create the graphic overlays
    private val graphicsOverlay: GraphicsOverlay by lazy { GraphicsOverlay() }

    // define a line symbol which will represent the river stream with its width defined.
    private val lineSymbol =
        SimpleLineSymbol(SimpleLineSymbolStyle.Solid, Color.cyan, 4f)

    private lateinit var polyline: Polyline

    private lateinit var features : List<Feature>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // authentication with an API key or named user is
        // required to access basemaps and other location services
        ArcGISEnvironment.apiKey = ApiKey.create(BuildConfig.API_KEY)
        lifecycle.addObserver(mapView)
        // some parts of the API require an Android Context to properly interact with Android system
        // features, such as LocationProvider and application resources
        ArcGISEnvironment.applicationContext = applicationContext

        val navigateButton = activityMainBinding.navigateButton

        // add the feature layer to the maps operational layer
        val geocacheMap = ArcGISMap(BasemapStyle.ArcGISStreets).apply {
            operationalLayers.add(featureLayer)
        }

        // apply the map to the mapView
        mapView.apply {
            // set the map to be displayed in the layout's map view
            map = geocacheMap
            // create graphics overlays to show the inputs and results of the spatial operation
            graphicsOverlays.add(graphicsOverlay)
            // set an initial view point
            setViewpoint(Viewpoint(34.06, -117.228, 5e5))

            // give any item selected on the mapView a green selection halo
            selectionProperties.color = Color.green

            // create a location display object
            val locationDisplay = mapView.locationDisplay
            // listen to the changes in the status of the location date source
            lifecycleScope.launch {
                locationDisplay.dataSource.start()
                    .onSuccess {
                        locationDisplay.setAutoPanMode(LocationDisplayAutoPanMode.Navigation)
                        // permission already granted, so start the location display
                        activityMainBinding.spinner.setSelection(1, true)
                    }.onFailure {
                        // check permissions to see if failure may be due to lack of permissions
                        requestPermissions()
                    }
            }

            // set an on touch listener on the map view
            lifecycleScope.launch {
                onSingleTapConfirmed.collect { tapEvent ->
                    // get the tapped coordinate
                    screenCoordinate = tapEvent.screenCoordinate
                    var features = getSelectedFeatureLayer(screenCoordinate)
                    navigateButton.isEnabled = true

                    // get the current location of the user
                    val currentPosition = locationDisplay.location.value?.position
                    if (currentPosition != null) {
                        // project the WGS84 point to Web mercator point
                        val webMercatorPoint =
                            GeometryEngine.projectOrNull(currentPosition, SpatialReference.webMercator())
                        val featuremapLocation = features[0].geometry?.let { extractMapLocation(it) }
                        if (featuremapLocation != null) {
                            // Do something with the extracted map location
                            println("Selected Feature Location - X: ${featuremapLocation.x}, Y: ${featuremapLocation.y}")
                        }
                        // create a polyline connecting the 2 points above
                        polyline = Polyline(listOf(webMercatorPoint!!, featuremapLocation!!))
                    }
                }
            }
        }
        // navigate to the destination point
        navigateButton.setOnClickListener {
            // disable button
            navigateButton.isEnabled = false
            // create a Graphic using the polyline geometry and the riverSymbol and add it to the GraphicsOverlay
            graphicsOverlay.graphics.add(Graphic(polyline, lineSymbol))
        }
    }

    /**
     * Displays the number of features selected on the given [screenCoordinate]
     */
    private suspend fun getSelectedFeatureLayer(screenCoordinate: ScreenCoordinate) : List<Feature> {
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
                featureLayer.selectFeatures(features)
                Snackbar.make(mapView, "${features.size} features selected", Snackbar.LENGTH_SHORT)
                    .show()
            }
            onFailure {
                val errorMessage = "Select feature failed: " + it.message
                Log.e(localClassName, errorMessage)
                Snackbar.make(mapView, errorMessage, Snackbar.LENGTH_SHORT).show()
            }
        }
        return features
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
                mapView.locationDisplay.dataSource.start().onSuccess {
                    activityMainBinding.spinner.setSelection(1, true)
                }
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
                mapView.locationDisplay.dataSource.start().onSuccess {
                    activityMainBinding.spinner.setSelection(1, true)
                }
            }
        } else {
            Snackbar.make(
                mapView,
                "Location permissions required to run this sample!",
                Snackbar.LENGTH_LONG
            ).show()
            // update UI to reflect that the location display did not actually start
            activityMainBinding.spinner.setSelection(0, true)
        }
    }

    private fun extractMapLocation(geometry: Geometry): Point {
        val mapViewSpatialReference = mapView.spatialReference.value
        val featureGeometry = GeometryEngine.projectOrNull(geometry, mapViewSpatialReference!!) as Point
        return featureGeometry
    }

}

