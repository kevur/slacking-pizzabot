package com.dragbone.slackbot

import com.ullink.slack.simpleslackapi.SlackSession
import com.ullink.slack.simpleslackapi.events.SlackMessagePosted
import com.ullink.slack.simpleslackapi.listeners.SlackMessagePostedListener
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SlackBot : SlackMessagePostedListener {
    val commandIndicator = "!"
    val channelName: String
    val commandMap: Map<String, ICommand>
    val botState: BotState
    val stateName: String

    private val cronCommands: Iterable<ICronTask>
    private val session: SlackSession

    constructor(session: SlackSession, channelName: String, commands: Iterable<ICommand>, cronCommands: Iterable<ICronTask>) {
        this.session = session
        this.channelName = channelName
        commandMap = commands.associateBy { it.name }
        if (commandMap.count() != commands.count()) {
            val duplicates = commands.groupBy { it.name }.filter { it.value.count() > 1 }.keys.joinToString()
            throw IllegalArgumentException("Commands with identical names added: $duplicates")
        }
        stateName = "$channelName.bot.json"
        botState = StateStore().getOrCreate(stateName) { BotState() }
        this.cronCommands = cronCommands

        val slackChannel = session.channels.single { it.name == channelName }
        Executors.newSingleThreadScheduledExecutor().scheduleAtFixedRate({
            cronCommands
                    .flatMap { it.run(slackChannel) }
                    .forEach { session.sendMessage(slackChannel, it) }
            // Save state after command execution
            StateStore().save(stateName, botState)
        }, 10, 60, TimeUnit.SECONDS)
    }

    override fun onEvent(event: SlackMessagePosted, session: SlackSession) {
        val messageContent = event.messageContent
        val channel = event.channel
        val sender = event.sender
        println("Received message in channel ${channel.name} from ${event.sender.id}, filtering for $channelName")
        if (messageContent.length == 0 || sender.isBot || channelName != channel.name)
            return

        val firstWord = messageContent.substringBefore(' ')

        // Check if message is a command
        if (firstWord.startsWith(commandIndicator)) {
            // Find command
            val commandName = firstWord.removePrefix(commandIndicator)
            println("Command: $commandName")

            try {
                if (!commandMap.containsKey(commandName))
                    throw CommandNotFoundException(commandName)
                val command = commandMap[commandName]!!
                if (command.requiresAdmin && !botState.admins.contains(event.sender.id)) {
                    session.sendMessage(channel, "Command $commandName requires admin rights.")
                    throw Exception("Command $commandName requires admin rights.")
                }

                // Execute
                val messages = command.execute(messageContent.substringAfter(' '), channel, sender)

                println("Going to send messages: $messages")

                // Send messages
                session.sendMessage(channel, messages.joinToString("\n"))
            } catch (e: Exception) {
                println("Exception while executing command $commandName: $e")
                e.printStackTrace()
            }

            // Save state after command execution
            StateStore().save(stateName, botState)
        }
    }
}

class BotState {
    val admins = mutableSetOf<String>()
}

class CommandNotFoundException(commandName: String) : Exception("Could not find command '$commandName'.")