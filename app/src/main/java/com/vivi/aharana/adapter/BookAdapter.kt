package com.vivi.aharana.adapter

import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.vivi.aharana.R
import com.vivi.aharana.model.BookItem
import com.vivi.aharana.util.CoverColorUtils
import com.vivi.aharana.LibraryStoryStatus
import com.vivi.aharana.LibraryStoryStatusLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class BookAdapter(
    private val books: List<BookItem>,
    private val onBookClicked: (BookItem) -> Unit,
    private val onBookLongClicked: (BookItem) -> Unit,
    private val onSelectionChanged: () -> Unit = {},
    private val statusScope: CoroutineScope
) : RecyclerView.Adapter<BookAdapter.BookViewHolder>() {

    private val selectedIds = mutableSetOf<String>()
    var selectionMode = false
        private set

    private val _books = books.toMutableList()
    private var statusLoaderInstance: LibraryStoryStatusLoader? = null

    private fun loader(context: android.content.Context): LibraryStoryStatusLoader =
        statusLoaderInstance ?: LibraryStoryStatusLoader(context).also { statusLoaderInstance = it }

    inner class BookViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val textTitle: TextView = itemView.findViewById(R.id.textTitle)
        private val textAuthor: TextView = itemView.findViewById(R.id.textAuthor)
        private val imageCover: ImageView = itemView.findViewById(R.id.imageCover)
        private val textChapters: TextView = itemView.findViewById(R.id.textChapters)
        private val textStatus: TextView = itemView.findViewById(R.id.textLibraryStatus)
        private var statusJob: Job? = null
        private var boundPath: String? = null

        fun recycle() {
            statusJob?.cancel()
            statusJob = null
            boundPath = null
            textStatus.setText(R.string.library_status_loading)
        }

        private fun bindStatus(book: BookItem) {
            recycle()
            boundPath = book.uriString
            textStatus.setText(R.string.library_status_loading)
            val statusLoader = loader(itemView.context)
            statusJob = statusScope.launch {
                try {
                    val status = statusLoader.load(book)
                    if (boundPath == book.uriString) textStatus.text = itemView.context.getString(
                        R.string.library_status_format, LibraryStoryStatus.label(status))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    if (boundPath == book.uriString) textStatus.setText(R.string.library_status_unavailable)
                }
            }
        }

        fun bind(book: BookItem) {
            bindStatus(book)
            textTitle.text = book.title.ifBlank { "Untitled" }
            textAuthor.text = book.author.ifBlank { "Unknown author" }
            textChapters.text = if (book.chapters > 0)
                itemView.resources.getQuantityString(R.plurals.library_chapter_count, book.chapters, book.chapters) else ""
            val selected = selectedIds.contains(book.uriString)
            itemView.isSelected = selected
            val card = itemView as com.google.android.material.card.MaterialCardView
            val res = card.context.resources
            val strokeColor = if (selected) {
                card.context.getColor(com.vivi.aharana.R.color.fanficfare_primary)
            } else {
                card.context.getColor(android.R.color.transparent)
            }
            val strokeWidth = if (selected) res.getDimensionPixelSize(R.dimen.selection_stroke) else 0
            card.strokeColor = strokeColor
            card.strokeWidth = strokeWidth
            itemView.setOnClickListener {
                if (selectionMode) {
                    toggleSelection(book)
                } else {
                    onBookClicked(book)
                }
            }
            itemView.setOnLongClickListener {
                onBookLongClicked(book)
                true
            }

            // Set dynamic content description for accessibility
            imageCover.contentDescription = "Cover for ${book.title} by ${book.author}"

            val cover = book.coverUriString
            if (cover?.isNotBlank() == true && cover.startsWith("data:")) {
                try {
                    val comma = cover.indexOf(",")
                    if (comma > 0) {
                        val base64 = cover.substring(comma + 1)
                        val bytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
                        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        if (bitmap != null) {
                            imageCover.setImageBitmap(bitmap)
                            imageCover.visibility = View.VISIBLE
                            return
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.d("FFF-Cover", "decodeByteArray failed: type=" + e.javaClass.simpleName + " msg=" + (e.message ?: ""))
                }
            }
            // No cover data - generate placeholder
            showPlaceholder(book)
        }

        private fun showPlaceholder(book: BookItem) {
            val bgColor = CoverColorUtils.colorFromString(book.title)
            val textColor = CoverColorUtils.contrastingTextColor(bgColor)
            val initials = CoverColorUtils.initialsFromTitle(book.title, book.author)

            val width = imageCover.width
            val height = imageCover.height
            if (width <= 0 || height <= 0) {
                imageCover.post {
                    if (imageCover.width > 0 && imageCover.height > 0) showPlaceholder(book)
                }
                return
            }
            val insetPx = (3 * imageCover.resources.displayMetrics.density).toInt()
            val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val paint = Paint().apply {
                isAntiAlias = true
                color = bgColor
                style = Paint.Style.FILL
            }
            canvas.drawRect(
                insetPx.toFloat(),
                insetPx.toFloat(),
                (width - insetPx).toFloat(),
                (height - insetPx).toFloat(),
                paint
            )

            val textPaint = Paint().apply {
                isAntiAlias = true
                color = textColor
                textSize = (minOf(width, height) * 0.38f)
                typeface = Typeface.DEFAULT_BOLD
                textAlign = Paint.Align.CENTER
            }
            val bounds = android.graphics.Rect()
            textPaint.getTextBounds(initials, 0, initials.length, bounds)
            canvas.drawText(
                initials,
                width / 2f,
                height / 2f + bounds.height() / 2f - bounds.bottom,
                textPaint
            )
            imageCover.setImageBitmap(bitmap)
            imageCover.visibility = View.VISIBLE
        }
    }

    fun isSelectionMode(): Boolean = selectionMode

    fun enterSelectionMode(book: BookItem) {
        selectionMode = true
        selectedIds.clear()
        selectedIds.add(book.uriString)
        notifyDataSetChanged()
        onSelectionChanged()
    }

    /** Enter selection mode with nothing selected, so the user picks the books. */
    fun enterSelectionMode() {
        selectionMode = true
        selectedIds.clear()
        notifyDataSetChanged()
        onSelectionChanged()
    }

    fun toggleSelection(book: BookItem) {
        if (selectedIds.contains(book.uriString)) {
            selectedIds.remove(book.uriString)
        } else {
            selectedIds.add(book.uriString)
        }
        if (selectedIds.isEmpty()) {
            selectionMode = false
        }
        notifyDataSetChanged()
        onSelectionChanged()
    }

    fun clearSelection() {
        selectionMode = false
        selectedIds.clear()
        notifyDataSetChanged()
    }

    // Must read the CURRENT list (_books), not the constructor list: the adapter is
    // built before the library loads, so `books` can be empty/stale and every
    // selection lookup would come back empty ("0 selected", Merge/Update seeing
    // nothing selected).
    fun getSelectedBooks(): List<BookItem> = _books.filter { selectedIds.contains(it.uriString) }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BookViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_book, parent, false)
        return BookViewHolder(view)
    }

    override fun onBindViewHolder(holder: BookViewHolder, position: Int) {
        holder.bind(_books[position])
    }

    override fun onViewRecycled(holder: BookViewHolder) {
        holder.recycle()
        super.onViewRecycled(holder)
    }

    override fun getItemCount(): Int = _books.size

    fun updateBooks(newBooks: List<BookItem>) {
        statusLoaderInstance?.invalidate()
        // Keep the selection for books that are still present; drop ids that are
        // gone (deleted or filtered out). Clearing unconditionally here is what
        // used to make a background book update silently drop the selection.
        val present = newBooks.mapTo(mutableSetOf()) { it.uriString }
        selectedIds.retainAll(present)
        _books.clear()
        _books.addAll(newBooks)
        notifyDataSetChanged()
    }
}
