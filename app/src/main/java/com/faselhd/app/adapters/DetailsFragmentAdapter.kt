import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.faselhd.app.fragments.CommentsFragment
import com.faselhd.app.fragments.MoreLikeThisFragment

class DetailsFragmentAdapter(fragmentActivity: FragmentActivity) : FragmentStateAdapter(fragmentActivity) {
    override fun getItemCount(): Int = 2 // We have two tabs

    override fun createFragment(position: Int): Fragment {
        return when (position) {
            0 -> MoreLikeThisFragment()
            1 -> CommentsFragment()
            else -> throw IllegalStateException("Invalid position: $position")
        }
    }
}