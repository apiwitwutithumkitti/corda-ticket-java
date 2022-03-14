package com.template.flows;

import co.paralleluniverse.fibers.Suspendable;
import com.template.contracts.TicketContract;
import com.template.contracts.TicketContract.Commands.Buy;
import com.template.states.TicketState;
import net.corda.core.contracts.Command;
import net.corda.core.contracts.UniqueIdentifier;
import net.corda.core.flows.*;
import net.corda.core.identity.Party;
import net.corda.core.node.StatesToRecord;
import net.corda.core.transactions.SignedTransaction;
import net.corda.core.transactions.TransactionBuilder;
import net.corda.core.utilities.ProgressTracker;
import net.corda.core.utilities.ProgressTracker.Step;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;


public interface BuyFlows {

    @InitiatingFlow
    @StartableByRPC
    class BuyInitiator extends FlowLogic<SignedTransaction> {

        @NotNull
        private final Party spectator;
        @NotNull
        private final int section;

        @NotNull
        private final ProgressTracker progressTracker;
        private final static Step GENERATING_TRANSACTION = new Step("Generating transaction based on parameters.");
        private final static Step VERIFYING_TRANSACTION = new Step("Verifying contract constraints.");
        private final static Step SIGNING_TRANSACTION = new Step("Signing transaction with our private key.");
        private final static Step FINALISING_TRANSACTION = new Step(
                "Obtaining notary signature and recording transaction"){
            @Override
            public ProgressTracker childProgressTracker() {
                return FinalityFlow.Companion.tracker();
            }
        };

        @NotNull
        public static ProgressTracker tracker() {
            return new ProgressTracker(GENERATING_TRANSACTION, VERIFYING_TRANSACTION, SIGNING_TRANSACTION, FINALISING_TRANSACTION);
        }

        public BuyInitiator(@NotNull Party spectator, int section) {
            this.spectator = spectator;
            this.section = section;
            this.progressTracker = tracker();
        }

        @Override
        @NotNull
        public ProgressTracker getProgressTracker() {
            return progressTracker;
        }

        @Suspendable
        @Override
        public SignedTransaction call() throws FlowException {
           final Party issuer = getOurIdentity();

           final Party notary = getServiceHub().getNetworkMapCache().getNotaryIdentities().get(0);

           progressTracker.setCurrentStep(GENERATING_TRANSACTION);

           final TicketState output = new TicketState(issuer, this.spectator, this.section, new UniqueIdentifier());

            final Command<Buy> commandBuy = new Command<>(new Buy(), issuer.getOwningKey());

            final TransactionBuilder txBuilder = new TransactionBuilder(notary)
                    .addCommand(commandBuy)
                    .addOutputState(output, TicketContract.ID);

            progressTracker.setCurrentStep(VERIFYING_TRANSACTION);

            txBuilder.verify(getServiceHub());

            progressTracker.setCurrentStep(SIGNING_TRANSACTION);
            final SignedTransaction fullySignedTx = getServiceHub().signInitialTransaction(txBuilder);

            progressTracker.setCurrentStep(FINALISING_TRANSACTION);
            FlowSession spectatorSession = initiateFlow(this.spectator);
            final SignedTransaction notarised = subFlow(new FinalityFlow(fullySignedTx, Arrays.asList(spectatorSession)));
            getServiceHub().recordTransactions(StatesToRecord.ALL_VISIBLE, Arrays.asList(notarised));
            return notarised;
        }
    }

    @InitiatedBy(BuyInitiator.class)
    class BuyResponder extends FlowLogic<SignedTransaction> {

        @NotNull
        private final FlowSession InitiatorSession;

        public BuyResponder(@NotNull FlowSession initiatorSession) {
            InitiatorSession = initiatorSession;
        }

        @Suspendable
        @Override
        public SignedTransaction call() throws FlowException {
            return subFlow(new ReceiveFinalityFlow(InitiatorSession));
        }
    }
}
