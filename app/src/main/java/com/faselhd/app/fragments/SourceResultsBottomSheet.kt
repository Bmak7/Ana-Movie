import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.GridLayoutManager
import com.example.myapplication.R
import com.example.myapplication.databinding.BottomSheetSourceResultsBinding
import com.faselhd.app.adapters.AnimeAdapter
import com.faselhd.app.models.SAnime
import com.faselhd.app.widgets.GridSpacingItemDecoration
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class SourceResultsBottomSheet : BottomSheetDialogFragment() {

    // 1. Define the interface (the contract)
    interface OnAnimeSelectedListener {
        fun onAnimeSelected(anime: SAnime)
    }

    // 2. Create a variable to hold the listener (the activity)
    private var listener: OnAnimeSelectedListener? = null

    // 3. Attach the listener when the fragment attaches to the activity
    override fun onAttach(context: Context) {
        super.onAttach(context)
        if (context is OnAnimeSelectedListener) {
            listener = context
        } else {
            // This is a safety check. The app will crash if the activity doesn't implement the interface.
            throw RuntimeException("$context must implement OnAnimeSelectedListener")
        }
    }

    // 4. Detach the listener to prevent memory leaks
    override fun onDetach() {
        super.onDetach()
        listener = null
    }


    private var _binding: BottomSheetSourceResultsBinding? = null
    private val binding get() = _binding!!

    private val animeList: ArrayList<SAnime> by lazy {
        arguments?.getParcelableArrayList(ARG_ANIME_LIST) ?: arrayListOf()
    }

    private val sourceName: String by lazy {
        arguments?.getString(ARG_SOURCE_NAME) ?: "Results"
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = BottomSheetSourceResultsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.bottomSheetTitle.text = "All from $sourceName"

        val animeAdapter = AnimeAdapter(AnimeAdapter.ViewType.GRID) { anime ->
            listener?.onAnimeSelected(anime)
            dismiss()
        }

        // --- NEW CODE ---
        // Define your desired spacing in pixels. It's best to use a dimens resource.
        // For example, 8dp. Let's assume you have a <dimen name="grid_spacing">8dp</dimen> in a dimens.xml file
        val spacingInPixels = resources.getDimensionPixelSize(R.dimen.grid_spacing)
        // --- END NEW CODE ---

        binding.bottomSheetRecyclerView.apply {
            layoutManager = GridLayoutManager(context, 3)

            // --- ADD THIS LINE ---
            // Make sure you don't add the decoration more than once if onViewCreated can be called multiple times
            if (itemDecorationCount == 0) {
                addItemDecoration(GridSpacingItemDecoration(3, spacingInPixels, true))
            }
            // --- END ADDITION ---

            adapter = animeAdapter
        }
        animeAdapter.submitList(animeList)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val ARG_ANIME_LIST = "anime_list"
        private const val ARG_SOURCE_NAME = "source_name"

        fun newInstance(sourceName: String, results: List<SAnime>): SourceResultsBottomSheet {
            return SourceResultsBottomSheet().apply {
                arguments = Bundle().apply {
                    putParcelableArrayList(ARG_ANIME_LIST, ArrayList(results))
                    putString(ARG_SOURCE_NAME, sourceName)
                }
            }
        }
    }
}