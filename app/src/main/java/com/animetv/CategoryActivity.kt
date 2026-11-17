package com.animetv

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

/** A provider genre; names match the provider's own menu. */
data class Genre(val slug: String, val name: String) {
    fun open(context: Context) {
        context.startActivity(Intent(context, SearchActivity::class.java).apply {
            putExtra("genre", slug)
            putExtra("genre_name", name)
        })
    }
}

/** Full genre grid, opened from the navigation rail's "Thể loại". */
class CategoryActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_category)
        val grid = findViewById<RecyclerView>(R.id.category_grid)
        grid.layoutManager = GridLayoutManager(this, COLUMNS)
        grid.setHasFixedSize(true)
        grid.adapter = GenreAdapter(GENRES)
        grid.post { grid.getChildAt(0)?.requestFocus() }
    }

    companion object {
        private const val COLUMNS = 4
        val GENRES = listOf(
            Genre("tu-tien", "Tu Tiên"),
            Genre("kiem-hiep", "Kiếm Hiệp"),
            Genre("co-trang", "Cổ Trang"),
            Genre("huyen-huyen", "Huyền Huyễn"),
            Genre("khoa-huyen", "Khoa Huyễn"),
            Genre("ky-ao", "Kỳ Ảo"),
            Genre("huyen-nghi", "Huyền Nghi"),
            Genre("canh-ky", "Cạnh Kỹ"),
            Genre("da-su", "Dã Sử"),
            Genre("do-thi", "Đô Thị"),
            Genre("dong-nhan", "Đồng Nhân"),
            Genre("hoat-hinh", "Hoạt Hình"),
        )
    }
}

private class GenreAdapter(private val genres: List<Genre>) : RecyclerView.Adapter<GenreAdapter.VH>() {
    override fun getItemCount() = genres.size
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.tile_genre, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val genre = genres[position]
        holder.title.text = genre.name
        holder.itemView.contentDescription = holder.itemView.resources.getString(R.string.browse_genre, genre.name)
        holder.itemView.setOnClickListener { genre.open(it.context) }
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.genre_name)
    }
}
