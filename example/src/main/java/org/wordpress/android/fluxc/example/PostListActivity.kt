package org.wordpress.android.fluxc.example

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.support.v7.app.AppCompatActivity
import android.support.v7.widget.DividerItemDecoration
import android.support.v7.widget.LinearLayoutManager
import android.support.v7.widget.RecyclerView
import android.support.v7.widget.RecyclerView.ViewHolder
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import dagger.android.AndroidInjection
import kotlinx.android.synthetic.main.post_list_activity.*
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import org.wordpress.android.fluxc.Dispatcher
import org.wordpress.android.fluxc.generated.PostActionBuilder
import org.wordpress.android.fluxc.model.ListModel.ListType
import org.wordpress.android.fluxc.model.PostModel
import org.wordpress.android.fluxc.model.SiteModel
import org.wordpress.android.fluxc.store.ListData
import org.wordpress.android.fluxc.store.ListItemInterface
import org.wordpress.android.fluxc.store.ListStore
import org.wordpress.android.fluxc.store.ListStore.OnListChanged
import org.wordpress.android.fluxc.store.PostStore
import org.wordpress.android.fluxc.store.PostStore.OnSinglePostFetched
import org.wordpress.android.fluxc.store.PostStore.RemotePostPayload
import org.wordpress.android.fluxc.store.SiteStore
import javax.inject.Inject

private const val LOCAL_SITE_ID = "LOCAL_SITE_ID"

class PostListActivity : AppCompatActivity() {
    @Inject internal lateinit var dispatcher: Dispatcher
    @Inject internal lateinit var listStore: ListStore
    @Inject internal lateinit var postStore: PostStore
    @Inject internal lateinit var siteStore: SiteStore

    private val listType = ListType.POSTS_ALL
    private lateinit var site: SiteModel
    private var postListAdapter: PostListAdapter? = null
    private lateinit var listData: ListData<PostModel>

    override fun onCreate(savedInstanceState: Bundle?) {
        AndroidInjection.inject(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.post_list_activity)

        dispatcher.register(this)
        site = siteStore.getSiteByLocalId(intent.getIntExtra(LOCAL_SITE_ID, 0))
        listData = getListDataFromStore()

        setupViews()

        listData.refresh()
    }

    private fun setupViews() {
        recycler.layoutManager = LinearLayoutManager(this, LinearLayoutManager.VERTICAL, false)
        recycler.addItemDecoration(DividerItemDecoration(this, DividerItemDecoration.VERTICAL))

        postListAdapter = PostListAdapter(this, listData)
        recycler.adapter = postListAdapter

        swipeToRefresh.setOnRefreshListener {
            listData.refresh()
        }
    }

    private fun refreshListData() {
        listData = getListDataFromStore()
        swipeToRefresh.isRefreshing = listData.isFetchingFirstPage
        loadingMoreProgressBar.visibility = if (listData.isLoadingMore) View.VISIBLE else View.GONE
        postListAdapter?.setListData(listData)
    }

    private fun getListDataFromStore(): ListData<PostModel> =
        listStore.getList(site, listType, object : ListItemInterface<PostModel> {
            override fun fetchItem(remoteItemId: Long) {
                val postToFetch = PostModel()
                postToFetch.remotePostId = remoteItemId
                val payload = RemotePostPayload(postToFetch, site)
                dispatcher.dispatch(PostActionBuilder.newFetchPostAction(payload))
            }

            override fun getItem(remoteItemId: Long): PostModel? {
                return postStore.getPostByRemotePostId(remoteItemId, site)
            }
        })

    @Subscribe(threadMode = ThreadMode.MAIN)
    @Suppress("unused")
    fun onListChanged(event: OnListChanged) {
        if (event.localSiteId != site.id || event.listType != listType || event.isError) {
            return
        }
        refreshListData()
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    @Suppress("unused")
    fun onSinglePostFetched(event: OnSinglePostFetched) {
        if (event.isError || event.localSiteId != site.id) {
            return
        }
        postListAdapter?.onItemChanged(event.remotePostId)
    }

    companion object {
        fun newInstance(context: Context, localSiteId: Int): Intent {
            val intent = Intent(context, PostListActivity::class.java)
            intent.putExtra(LOCAL_SITE_ID, localSiteId)
            return intent
        }
    }

    private class PostListAdapter(
        context: Context,
        private var data: ListData<PostModel>
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private val layoutInflater = LayoutInflater.from(context)

        fun setListData(newData: ListData<PostModel>) {
            val shouldUpdate = this.data.hasDataChanged(newData)
            data = newData
            if (shouldUpdate) {
                notifyDataSetChanged()
            }
        }

        fun onItemChanged(remoteItemId: Long) {
            val index = data.indexOfItem(remoteItemId)
            if (index != null) {
                notifyItemChanged(index)
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = layoutInflater.inflate(R.layout.post_list_row, parent, false)
            return PostViewHolder(view)
        }

        override fun getItemCount(): Int {
            return data.size
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val postHolder = holder as PostViewHolder
            val postModel = data.getRemoteItem(position)
            postHolder.postTitle.text = postModel?.title ?: ""
        }

        private class PostViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val postTitle: TextView = itemView.findViewById(R.id.post_list_row_post_title)
        }
    }
}