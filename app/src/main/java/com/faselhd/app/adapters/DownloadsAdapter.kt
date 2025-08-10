


package com.faselhd.app.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.myapplication.R
import com.faselhd.app.models.Download
import com.faselhd.app.models.DownloadState
import com.google.android.material.button.MaterialButton // <-- ADD THIS IMPORT


class DownloadsAdapter(
    private val onActionClick: (Download, DownloadAction) -> Unit
) : ListAdapter<Download, DownloadsAdapter.ViewHolder>(DownloadDiffCallback) {

    enum class DownloadAction { PAUSE, RESUME, CANCEL, PLAY }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_download, parent, false)
        return ViewHolder(view, onActionClick)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class ViewHolder(
        view: View,
        private val onActionClick: (Download, DownloadAction) -> Unit
    ) : RecyclerView.ViewHolder(view) {
        private val thumbnail: ImageView = view.findViewById(R.id.thumbnail_image)
        private val animeTitle: TextView = view.findViewById(R.id.anime_title_text)
        private val episodeName: TextView = view.findViewById(R.id.episode_name_text)
        private val statusText: TextView = view.findViewById(R.id.status_text)
        private val timeLeftText: TextView = view.findViewById(R.id.time_left_text) // New TextView
        private val progressBar: ProgressBar = view.findViewById(R.id.download_progress_bar)
        private val btnPauseResume: MaterialButton = view.findViewById(R.id.btn_pause_resume)
        private val btnCancel: MaterialButton = view.findViewById(R.id.btn_cancel)
        private var currentItem: Download? = null

        init {
            btnCancel.setOnClickListener {
                currentItem?.let { onActionClick(it, DownloadAction.CANCEL) }
            }
            // A click on the whole item will trigger the PLAY action
            itemView.setOnClickListener {
                currentItem?.let {
                    if (it.downloadState == DownloadState.COMPLETED) {
                        onActionClick(it, DownloadAction.PLAY)
                    }
                }
            }
        }

        fun bind(item: Download) {
            currentItem = item
            animeTitle.text = item.animeTitle
            episodeName.text = item.episodeName
            Glide.with(itemView.context).load(item.thumbnailUrl).into(thumbnail)
            progressBar.isIndeterminate = item.downloadState == DownloadState.QUEUED || (item.downloadState == DownloadState.DOWNLOADING && item.progress <= 0)
            progressBar.progress = item.progress

            // We don't have timeLeft from ExoPlayer, so hide it or show progress.
            // timeLeftText.visibility = View.GONE
            statusText.text = "${item.downloadState.name.capitalize()}"

            when (item.downloadState) {
                DownloadState.DOWNLOADING -> {
                    statusText.text = "Downloading: ${item.progress}%"
                    progressBar.visibility = View.VISIBLE
                    btnPauseResume.text = "Pause"
                    btnPauseResume.setIconResource(R.drawable.ic_pause)
                    btnPauseResume.setOnClickListener { onActionClick(item, DownloadAction.PAUSE) }
                    btnPauseResume.visibility = View.VISIBLE
                    btnCancel.visibility = View.VISIBLE
                }
                DownloadState.COMPLETED -> {
                    statusText.text = "Completed"
                    progressBar.visibility = View.GONE
                    btnPauseResume.text = "Play"
                    btnPauseResume.setIconResource(R.drawable.ic_play_arrow)
                    btnPauseResume.setOnClickListener { onActionClick(item, DownloadAction.PLAY) }
                    btnPauseResume.visibility = View.VISIBLE
                    btnCancel.visibility = View.VISIBLE
                }
                DownloadState.PAUSED -> {
                    statusText.text = "Paused: ${item.progress}%"
                    progressBar.visibility = View.VISIBLE
                    btnPauseResume.text = "Resume"
                    btnPauseResume.setIconResource(R.drawable.ic_play_arrow)
                    btnPauseResume.setOnClickListener { onActionClick(item, DownloadAction.RESUME) }
                    btnPauseResume.visibility = View.VISIBLE
                    btnCancel.visibility = View.VISIBLE
                }
                DownloadState.QUEUED -> {
                    progressBar.visibility = View.VISIBLE
                    btnPauseResume.visibility = View.GONE
                    btnCancel.visibility = View.VISIBLE
                }
                DownloadState.FAILED -> {
                    statusText.text = "Failed"
                    progressBar.visibility = View.GONE
                    btnPauseResume.visibility = View.GONE // Could add a "Retry" button here
                    btnCancel.visibility = View.VISIBLE
                }
                else -> {
                    btnPauseResume.visibility = View.GONE
                    btnCancel.visibility = View.VISIBLE
                }
            }
        }
    }
}

object DownloadDiffCallback : DiffUtil.ItemCallback<Download>() {
    override fun areItemsTheSame(oldItem: Download, newItem: Download): Boolean {
        return oldItem.episodeUrl == newItem.episodeUrl
    }
    override fun areContentsTheSame(oldItem: Download, newItem: Download): Boolean {
        return oldItem == newItem
    }
}