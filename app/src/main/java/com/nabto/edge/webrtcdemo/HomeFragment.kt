package com.nabto.edge.webrtcdemo

import android.content.Context
import android.os.Bundle
import android.view.*
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.MenuProvider
import androidx.fragment.app.Fragment
import androidx.fragment.app.findFragment
import androidx.lifecycle.*
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.progressindicator.CircularProgressIndicator
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import com.nabto.edge.webrtcdemo.*

private object DeviceMenu {
    const val EDIT = 0
    const val DELETE = 1
}

/**
 * RecyclerView Adapter for updating the views per item in the list.
 */
class DeviceListAdapter(val context: Context) : RecyclerView.Adapter<DeviceListAdapter.ViewHolder>() {
    class ViewHolder(val view: View, val context: Context) : RecyclerView.ViewHolder(view) {
        lateinit var device: Device
        val title: TextView = view.findViewById(R.id.home_device_item_title)
        val status: TextView = view.findViewById(R.id.home_device_item_subtitle)
        val connectionStatusView: ImageView = view.findViewById(R.id.home_device_item_connection)
        val progressBar: CircularProgressIndicator = view.findViewById(R.id.home_device_item_loading)
    }

    private var dataSet: List<DeviceBookmark> = listOf()

    fun submitDeviceList(devices: List<DeviceBookmark>) {
        dataSet = devices
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int
    ): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.home_device_list_item, parent, false)
        return ViewHolder(view, context)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.device = dataSet[position].device
        holder.title.text = dataSet[position].device.getDeviceNameOrElse(context.getString(R.string.unnamed_device))
        holder.status.text = dataSet[position].device.deviceId
        holder.view.setOnClickListener {
            it.findFragment<HomeFragment>().onDeviceClick(dataSet[position].device)
        }

        val status = dataSet[position].status
        val (color, icon) = when (status) {
            BookmarkStatus.ONLINE -> R.color.green to R.drawable.ic_baseline_check_circle
            BookmarkStatus.UNPAIRED -> R.color.yellow to R.drawable.ic_baseline_lock
            BookmarkStatus.CONNECTING -> R.color.red to R.drawable.ic_baseline_warning
            BookmarkStatus.OFFLINE -> R.color.red to R.drawable.ic_baseline_warning
            BookmarkStatus.WRONG_FINGERPRINT -> R.color.red to R.drawable.ic_baseline_lock
        }

        if (status == BookmarkStatus.CONNECTING) {
            holder.connectionStatusView.swapWith(holder.progressBar)
        } else {
            holder.progressBar.swapWith(holder.connectionStatusView)
            holder.connectionStatusView.setImageResource(icon)
            holder.connectionStatusView.setColorFilter(
                ContextCompat.getColor(
                    holder.view.context,
                    color
                )
            )
        }
    }

    override fun getItemCount() = dataSet.size
}


/**
 * Fragment for fragment_home.xml.
 */
class HomeFragment : Fragment(), MenuProvider {
    private val TAG = javaClass.simpleName

    private val manager: NabtoConnectionManager by inject()
    private val database: DeviceDatabase by inject()
    private val bookmarks: NabtoBookmarksRepository by inject()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.fragment_home, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        requireActivity().addMenuProvider(this, viewLifecycleOwner, Lifecycle.State.RESUMED)
        val deviceListAdapter = DeviceListAdapter(requireContext())

        val recycler = view.findViewById<RecyclerView>(R.id.home_recycler)
        val layoutManager = LinearLayoutManager(activity)
        recycler.adapter = deviceListAdapter
        recycler.layoutManager = layoutManager

        /*
        view.findViewById<Button>(R.id.home_pair_new_button).setOnClickListener {
            requireAppActivity().navigateToPairing()
        }
         */
        view.findViewById<Button>(R.id.home_pair_new_button).visibility = View.GONE


        lifecycleScope.launch {
            whenResumed {
                bookmarks.getDevices().collect { devices ->
                    if (devices != null) {
                        deviceListAdapter.submitDeviceList(devices)
                        view.findViewById<View>(R.id.home_empty_layout).visibility =
                            if (devices.isEmpty()) View.VISIBLE else View.GONE
                        recycler.visibility = if (devices.isEmpty()) View.GONE else View.VISIBLE
                    }
                }
            }
        }

        val dividerItemDecoration = DividerItemDecoration(
            recycler.context,
            layoutManager.orientation
        )
        recycler.addItemDecoration(dividerItemDecoration)
        registerForContextMenu(recycler)
    }

    override fun onResume() {
        super.onResume()
        bookmarks.refresh()
    }

    fun onDeviceClick(device: Device) {
        val status = bookmarks.getStatus(device)
        if (status == BookmarkStatus.UNPAIRED) {
            bookmarks.release()
            findNavController().navigate(AppRoute.pairDevice(device.productId, device.deviceId))
        } else if (status == BookmarkStatus.WRONG_FINGERPRINT) {
            view?.snack("Fingerprint is different from expected! Please delete and redo pairing if this is deliberate.")
        } else if (status == BookmarkStatus.ONLINE) {
            bookmarks.disconnectBookmarksExcept(setOf(device))
            findNavController().navigate(
                AppRoute.appDevicePage(device.productId, device.deviceId)
            )
        } else {
            view?.snack("Device is offline. Attempting refresh.")
            // @TODO: send us into the device page if the connection succeeds.
            manager.connect(manager.requestConnection(device))
        }
    }

    override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
        menuInflater.inflate(R.menu.menu_main, menu)
    }

    override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
        if (menuItem.itemId == R.id.action_device_refresh) {
            bookmarks.refresh()
            return true
        }
        return false
    }
}