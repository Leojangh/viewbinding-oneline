@file:Suppress("UNUSED", "UNCHECKED_CAST", "FunctionName")
@file:Keep

package com.genlz.android.viewbinding

import android.util.Log
import android.view.LayoutInflater
import android.view.View
import androidx.activity.ComponentActivity
import androidx.annotation.Keep
import androidx.annotation.LayoutRes
import androidx.databinding.DataBindingUtil
import androidx.databinding.ViewDataBinding
import androidx.fragment.app.Fragment
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.viewbinding.ViewBinding
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KProperty

private const val METHOD_INFLATE = "inflate"

private const val BINDING_SUFFIX = "Binding"

private const val METHOD_BIND = "bind"

private const val DATABINDING_CLASS = "androidx.databinding.ViewDataBinding"

val dataBindingEnable = try {
    Class.forName(DATABINDING_CLASS)
    true
} catch (e: ClassNotFoundException) {
    Log.d("ViewBindingToolKit", "dataBinding is not enable!")
    false
}

/**
 * A handy toolkit to use data binding or view binding,no need to consider complex lifecycle anymore.
 * Say goodbye to memory leak.
 *
 * There are some samples:
 *
 * case 1:
 * ```
 * class HomeFragment : Fragment() {
 *     ...
 *     private val binding: FragmentHomeBinding by viewBinding()
 *     ...
 *
 *     override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
 *         super.onViewCreated(view, savedInstanceState)
 *         //binding is ready to use
 *     }
 * }
 * ```
 * It's the easiest usage with reflection under hood,no aggressive.But perhaps
 * it has some constraints such as no support for customizing binding class name yet.
 * Because it is implemented by default conventions.
 *
 * case 2:
 * ```
 * class HomeFragment : Fragment(R.layout.fragment_home) {
 *     ...
 *     private val binding: FragmentHomeBinding by viewBinding()
 *     ...
 * }
 * ```
 * Pass in layout resource as constructor's argument manually,optional operation.
 *
 * case 3:
 * ```
 * class HomeFragment : Fragment() {
 *     ...
 *     private val binding by viewBinding(FragmentHomeBinding::bind)
 *     ...
 *     override fun onCreateView(
 *         inflater: LayoutInflater,
 *         container: ViewGroup?,
 *         savedInstanceState: Bundle?,
 *     ): View {
 *         return inflater.inflate(R.layout.fragment_home, container, false)
 *     }
 * }
 * ```
 * Supply a high-order function to bind view lazily,and then inflate view as usual without additional cost.
 *
 * Considering to supply the method reference and/or layout resource instead of default params
 * if reflection cost matters.
 *
 * @param bind usually XxxBinding::bind,aka the static method of
 * the generated binding class.Alternatively,you can invoke it using default parameters
 * which is the method found by reflection.
 *
 */
inline fun <reified VB : ViewBinding> Fragment.viewBinding(
    crossinline bind: (View) -> VB = { v ->
        if (ViewDataBinding::class.java.isAssignableFrom(VB::class.java)) { //DataBinding enable
            DataBindingUtil.bind<ViewDataBinding>(v) as VB
        } else { //just ViewBinding
            VB::class.java.getMethod("bind", View::class.java)(null, v) as VB
        }
    },
    @LayoutRes layoutId: Int = 0,
): ReadOnlyProperty<Fragment, VB> = object : ReadOnlyProperty<Fragment, VB> {

    private val TAG = "ViewBindingToolKit"

    private var binding: VB? = null

    private val mContentLayoutId = Fragment::class.java
        .getDeclaredField("mContentLayoutId")
        .apply { isAccessible = true }

    @LayoutRes
    private var cachedLayoutId = layoutId

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) {
                if (mContentLayoutId[owner] == 0) {
                    cachedLayoutId = when (cachedLayoutId) {
                        0 -> resources.getIdentifier(
                            VB::class.java.simpleName.toResourceName(),
                            "layout",
                            requireContext().packageName) //find by reflection
                        else -> cachedLayoutId //use cache
                    }

                    mContentLayoutId.apply {
                        this[owner] = cachedLayoutId
                        Log.d(TAG, "Set layout resource for $owner successfully.")
                    }
                }
            }
        })

        viewLifecycleOwnerLiveData.observe(this@viewBinding) { viewLifecycleOwner ->
            viewLifecycleOwner.lifecycle.addObserver(object : DefaultLifecycleObserver {
                override fun onDestroy(owner: LifecycleOwner) {
                    if (dataBindingEnable && binding is ViewDataBinding) {
                        (binding as ViewDataBinding).unbind()
                    }
                    Log.d(TAG, "Release binding:$binding for $owner")
                    binding = null
                }
            })
        }
    }

    override fun getValue(thisRef: Fragment, property: KProperty<*>): VB {
        binding?.let { return it } //lazily
        try {
            return bind(thisRef.requireView()).also {
                setDataBindingLifecycleOwner(it)
                binding = it
            }
        } catch (e: IllegalStateException) {
            throw IllegalStateException("Should not attempt to get bindings when Fragment views haven't been created yet." +
                    "i.e.,the fragment has not called onCreateView() or onDestroyView already called.",
                e)
        }
    }
}

/**
 * Convenient method to use data binding or view binding.
 * Sample:
 * ```
 *     ...
 *     private val binding by viewBinding<ActivityAppBinding>()
 *     ...
 * ```
 * Considering to supply method reference instead of default params if reflection cost matters.
 * All right,all done.
 * @param inflation the method to inflate view to ViewBinding.Usually aka XxxBinding::inflate method.
 * you can invoke it using default parameters which is the method
 * found by reflection.
 */
inline fun <reified VB : ViewBinding> ComponentActivity.viewBinding(
    crossinline inflation: (LayoutInflater) -> VB = {
        VB::class.java.getMethod("inflate", LayoutInflater::class.java)(null, it) as VB
    },
) = lazy {
    inflation(layoutInflater).apply {
        setDataBindingLifecycleOwner(this)
    }
}

fun <VB : ViewBinding> ComponentActivity.viewBinding(clazz: Class<VB>) = lazy {
    val binding = clazz.getMethod(
        METHOD_INFLATE,
        LayoutInflater::class.java
    )(null, layoutInflater) as VB
    binding.also { setDataBindingLifecycleOwner(it) }
}

/**
 * Sets the LifecycleOwner that should be used for observing changes of LiveData in this binding if
 * data binding enable,or do nothing.
 */
fun LifecycleOwner.setDataBindingLifecycleOwner(
    binding: ViewBinding,
) {
    if (dataBindingEnable && binding is ViewDataBinding) {
        binding.lifecycleOwner = if (this is Fragment) viewLifecycleOwner else this
    }
}

fun String.toBindingName(): String {
    if (this.endsWith(BINDING_SUFFIX)) return this
    return this.toClassName() + BINDING_SUFFIX
}

fun String.toResourceName(): String {
    val s = this.substring(0, length - 7)
    return buildString {
        append(s[0].lowercase())
        for (i in 1 until s.length) {
            if (s[i].isUpperCase()) {
                append("_")
                append(s[i].lowercase())
            } else {
                append(s[i])
            }
        }
    }
}

private fun String.toClassName() = buildString {
    for (item in this.split("-", "_")) {
        append(capitalize(item))
    }
}

fun capitalize(string: String?): String? {
    if (string.isNullOrEmpty()) return string
    return string[0].let {
        if (Character.isTitleCase(it)) {
            string
        } else {
            Character.toTitleCase(it).toString() + string.substring(1)
        }
    }
}

fun String.toCamelCase(): String {
    val split = this.split("_", "-")
    if (split.isEmpty()) return ""
    if (split.size == 1) return split[0].capitalizeUS()
    return split.joinToCamelCase()
}

fun String.toCamelCaseAsVar(): String {
    val split = this.split("_")
    if (split.isEmpty()) return ""
    if (split.size == 1) return split[0]
    return split.joinToCamelCaseAsVar()
}

fun List<String>.joinToCamelCase(): String = when (size) {
    0 -> throw IllegalArgumentException("invalid section size, cannot be zero")
    1 -> this[0].toCamelCase()
    else -> this.joinToString("", transform = String::toCamelCase)
}

fun List<String>.joinToCamelCaseAsVar(): String = when (size) {
    0 -> throw IllegalArgumentException("invalid section size, cannot be zero")
    1 -> this[0].toCamelCaseAsVar()
    else -> get(0).toCamelCaseAsVar() + drop(1).joinToCamelCase()
}

fun <T, R> Pair<T, T>.mapEach(body: (T) -> R): Pair<R, R> = body(first) to body(second)


fun String.capitalizeUS() = if (isEmpty()) {
    ""
} else {
    substring(0, 1).uppercase() + substring(1)
}

fun String.deCapitalizeUS() = if (isEmpty()) {
    ""
} else {
    substring(0, 1).lowercase() + substring(1)
}
