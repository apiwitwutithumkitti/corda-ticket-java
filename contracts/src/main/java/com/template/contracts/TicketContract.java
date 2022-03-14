package com.template.contracts;

import com.template.states.TicketState;
import net.corda.core.contracts.CommandData;
import net.corda.core.contracts.CommandWithParties;
import net.corda.core.contracts.Contract;
import net.corda.core.identity.Party;
import net.corda.core.transactions.LedgerTransaction;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static net.corda.core.contracts.ContractsDSL.requireSingleCommand;
import static net.corda.core.contracts.ContractsDSL.requireThat;

public class TicketContract implements Contract {

    @Override
    public void verify(@NotNull LedgerTransaction tx) throws IllegalArgumentException {

        public static final String ID = "com.template.contracts.TicketContract";

        final CommandWithParties<Commands> commandParty = requireSingleCommand(tx.getCommands(), Commands.class);
        final List<TicketState> inputs = tx.inputsOfType(TicketState.class);
        final List<TicketState> outputs = tx.outputsOfType(TicketState.class);

        if (commandParty.getValue() instanceof Commands.Buy) {
            requireThat(req -> {
                req.using("Any input can't be consumed when buying", inputs.isEmpty());
                req.using("Only 1 output can be issued", outputs.size() == 1);
                TicketState output = outputs.get(0);
                req.using("Issuer and Spectator can't be equals",
                        !output.getIssuer().equals(output.getSpectator()));
                req.using("Only LOW = 15, MED = 30 or HIGH = 50 section allowed",
                        output.getSection() == 15 || output.getSection() == 30 || output.getSection() == 50);
                req.using("Only 1 issuer must signed the transaction",
                        commandParty.getSigners().containsAll(Arrays.asList(output.getIssuer())
                                .stream()
                                .map(it -> it.getOwningKey()).collect(Collectors.toSet())));
                return null;
            });
        } else if (commandParty.getValue() instanceof Commands.Transfer) {
            requireThat(req -> {
                req.using("Only 1 input", inputs.size() == 1);
                req.using("Only 1 output", outputs.size() == 1);
                TicketState input = inputs.get(0);
                TicketState output = outputs.get(0);
                req.using("Input's issuer should be equals to output's Issuer"
                        , input.getIssuer().equals(output.getIssuer()));
                req.using("Input's Spectator and Output's Spectator can't be equals"
                        , !input.getSpectator().equals(output.getSpectator()));
                req.using("Section has to be conserved in the transfer"
                        , input.getSection() == output.getSection());
                req.using("Linear Id has to be conserved in the transfer"
                        , input.getLinearId() == output.getLinearId());
                List<Party> signers = Arrays.asList(input.getSpectator(), output.getSpectator());
                req.using("input's spectator and output's specator have to signed"
                        , commandParty.getSigners().containsAll(signers
                                .stream()
                                .map(it -> it.getOwningKey()).collect(Collectors.toSet()))
                );
                return null;
            });
        } else if (commandParty.getValue() instanceof Commands.Exit) {
            requireThat( req -> {
               req.using("Only 1 input can be exit", inputs.size() == 1);
               req.using("Any output should be create", outputs.size() == 0);
               TicketState input = inputs.get(0);
               req.using("Spectator and Issuer have to signed"
               , commandParty.getSigners().containsAll(input.getParticipants()
                       .stream()
                       .map(it -> it.getOwningKey()).collect(Collectors.toSet()))
               );
               return null;
            });
        } else {
            throw new IllegalArgumentException("Unknow command " + commandParty.getValue());
        }
    }

    public interface Commands extends CommandData {
        class Buy implements Commands {}
        ;

        class Transfer implements Commands {};

        class Exit implements Commands {};
    }
}
