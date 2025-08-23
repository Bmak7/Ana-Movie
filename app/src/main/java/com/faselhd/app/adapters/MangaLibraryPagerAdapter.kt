//
//// Fragment Adapter for ViewPager2
//import androidx.fragment.app.Fragment
//import androidx.fragment.app.FragmentActivity
//import androidx.viewpager2.adapter.FragmentStateAdapter
//import com.faselhd.app.ContinueReadingMangaFragment
//import com.faselhd.app.fragments.FavoriteMangaFragment
//import com.faselhd.app.MangaHistoryFragment
//
//class MangaLibraryPagerAdapter(fragmentActivity: FragmentActivity) : FragmentStateAdapter(fragmentActivity) {
//
//    override fun getItemCount(): Int = 3
//
//    override fun createFragment(position: Int): Fragment {
//        return when (position) {
//            0 -> ContinueReadingMangaFragment()
//            1 -> FavoriteMangaFragment()
//            2 -> MangaHistoryFragment()
//            else -> throw IllegalArgumentException("Invalid position $position")
//        }
//    }
//}
