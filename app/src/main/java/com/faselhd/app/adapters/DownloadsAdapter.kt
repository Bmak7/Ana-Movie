// In app/src/main/java/com/faselhd/app/adapters/DownloadsAdapter.kt

package com.faselhd.app.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.myapplication.R
import com.faselhd.app.models.Download
import com.faselhd.app.models.DownloadState
import com.google.android.material.button.MaterialButton // <-- **ADD THIS IMPORT**

class DownloadsAdapter(
    private val onAction: (Download, DownloadAction) -> Unit
) : ListAdapter<Download, DownloadsAdapter.ViewHolder>(DiffCallback()) {

    enum class DownloadAction {
        PLAY, PAUSE, RESUME, DELETE
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_download, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val download = getItem(position)
        holder.bind(download)
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val container: LinearLayout = itemView.findViewById(R.id.download_item_container)
        private val thumbnail: ImageView = itemView.findViewById(R.id.thumbnail_image)
        private val animeTitle: TextView = itemView.findViewById(R.id.anime_title_text)
        private val episodeName: TextView = itemView.findViewById(R.id.episode_name_text)
        private val statusText: TextView = itemView.findViewById(R.id.status_text)
        private val progressBar: ProgressBar = itemView.findViewById(R.id.download_progress_bar)

        // *** FIX: Change type from Button to MaterialButton ***
        private val btnPauseResume: MaterialButton = itemView.findViewById(R.id.btn_pause_resume)
        private val btnDelete: MaterialButton = itemView.findViewById(R.id.btn_delete)

        fun bind(download: Download) {
            animeTitle.text = download.animeTitle
            episodeName.text = download.episodeName
            Glide.with(itemView.context)
                .load(download.thumbnailUrl)
                .placeholder(R.drawable.placeholder_anime)
                .into(thumbnail)

            btnDelete.setOnClickListener { onAction(download, DownloadAction.DELETE) }

            container.setOnClickListener {
                if (download.downloadState == DownloadState.COMPLETED) {
                    onAction(download, DownloadAction.PLAY)
                }
            }

            when (download.downloadState) {
                DownloadState.COMPLETED -> {
                    statusText.text = "Completed"
                    progressBar.progress = 100
                    progressBar.visibility = View.VISIBLE
                    btnPauseResume.visibility = View.GONE
                }
                DownloadState.DOWNLOADING -> {
                    statusText.text = "Downloading: ${download.progress}%"
                    progressBar.isIndeterminate = false
                    progressBar.progress = download.progress
                    progressBar.visibility = View.VISIBLE
                    btnPauseResume.visibility = View.VISIBLE
                    btnPauseResume.text = "Pause"
                    btnPauseResume.setIconResource(R.drawable.ic_pause) // Now works
                    btnPauseResume.setOnClickListener { onAction(download, DownloadAction.PAUSE) }
                }
                DownloadState.PAUSED -> {
                    statusText.text = "Paused: ${download.progress}%"
                    progressBar.isIndeterminate = false
                    progressBar.progress = download.progress
                    progressBar.visibility = View.VISIBLE
                    btnPauseResume.visibility = View.VISIBLE
                    btnPauseResume.text = "Resume"
                    btnPauseResume.setIconResource(R.drawable.ic_play_arrow) // Now works
                    btnPauseResume.setOnClickListener { onAction(download, DownloadAction.RESUME) }
                }
                DownloadState.QUEUED -> {
                    statusText.text = "Queued..."
                    progressBar.isIndeterminate = true
                    progressBar.visibility = View.VISIBLE
                    btnPauseResume.visibility = View.GONE
                }
                DownloadState.FAILED -> {
                    statusText.text = "Failed"
                    progressBar.visibility = View.GONE
                    btnPauseResume.visibility = View.VISIBLE
                    btnPauseResume.text = "Retry"
                    btnPauseResume.setIconResource(R.drawable.ic_retry) // Now works
                    btnPauseResume.setOnClickListener { onAction(download, DownloadAction.RESUME) }
                }
                else -> {
                    statusText.text = "Not Downloaded"
                    progressBar.visibility = View.GONE
                    btnPauseResume.visibility = View.GONE
                }
            }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<Download>() {
        override fun areItemsTheSame(oldItem: Download, newItem: Download) = oldItem.episodeUrl == newItem.episodeUrl
        override fun areContentsTheSame(oldItem: Download, newItem: Download) = oldItem == newItem
    }
}