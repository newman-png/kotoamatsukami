package koto.core.ai

/** A scripted model. Answers come from [answer]; every request is kept for the test to inspect. */
class FakeLlm(private val answer: (LlmRequest, Int) -> String) : Llm {
    val requests = ArrayList<LlmRequest>()

    override fun complete(request: LlmRequest): String {
        requests += request
        return answer(request, requests.count { it.purpose == request.purpose } - 1)
    }

    fun count(purpose: Purpose) = requests.count { it.purpose == purpose }

    /** The text of the last user message of the [n]th request for [purpose]. */
    fun lastUser(purpose: Purpose, n: Int): String =
        requests.filter { it.purpose == purpose }[n].messages.last { it.role == Message.Role.USER }.content
}
