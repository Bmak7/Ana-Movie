import android.graphics.Rect
import android.view.View
import androidx.recyclerview.widget.RecyclerView

/**
 * Adds spacing to the start, end, and between items in a horizontal RecyclerView.
 */
class HorizontalSpacingItemDecoration(private val horizontalSpacing: Int) : RecyclerView.ItemDecoration() {

    override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
        val position = parent.getChildAdapterPosition(view)

        // Add spacing to the right of every item
        outRect.right = horizontalSpacing

        // Add spacing to the left of the very first item to balance the padding
        if (position == 0) {
            outRect.left = horizontalSpacing
        }
    }
}