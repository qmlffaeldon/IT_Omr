package com.example.it_scann

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class QuestionAdapter(private val total: Int) :
    RecyclerView.Adapter<QuestionAdapter.ViewHolder>() {

    // Holds answers for CURRENT test
    private var answers = IntArray(total) { -1 }

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val questionText: TextView = view.findViewById(R.id.questionText)
        val radioGroup: RadioGroup = view.findViewById(R.id.radioGroup)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_question, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.questionText.text = "Question ${position + 1}"

        // 🚨 VERY IMPORTANT: remove old listener before setting state
        holder.radioGroup.setOnCheckedChangeListener(null)

        // Restore saved answer (if any)
        val savedAnswer = answers[position]
        if (savedAnswer in 0..3) {
            (holder.radioGroup.getChildAt(savedAnswer) as RadioButton).isChecked = true
        } else {
            holder.radioGroup.clearCheck()
        }

        // Save answer when user selects
        holder.radioGroup.setOnCheckedChangeListener { _, checkedId ->
            answers[position] =
                holder.radioGroup.indexOfChild(
                    holder.radioGroup.findViewById(checkedId)
                )
        }
    }

    override fun getItemCount(): Int = total

    // 🔄 Called when switching test number
    fun setAnswers(newAnswers: IntArray) {
        answers = newAnswers.copyOf()
        notifyDataSetChanged()
    }

    // 📤 Used when saving current test answers
    fun getAnswers(): IntArray = answers.copyOf()
}
