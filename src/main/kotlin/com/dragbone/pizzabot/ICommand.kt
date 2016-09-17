package com.dragbone.pizzabot

import com.ullink.slack.simpleslackapi.SlackChannel
import com.ullink.slack.simpleslackapi.SlackUser

interface ICommand {
    val name: String
    fun execute(args: String, channel: SlackChannel, sender: SlackUser): Iterable<String>
}