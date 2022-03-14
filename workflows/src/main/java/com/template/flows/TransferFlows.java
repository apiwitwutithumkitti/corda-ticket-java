package com.template.flows;

import co.paralleluniverse.fibers.Suspendable;
import com.template.contracts.TicketContract;
import com.template.contracts.TicketContract.Commands.Transfer;
import com.template.states.TicketState;
import net.corda.core.contracts.Command;
import net.corda.core.contracts.ContractState;
import net.corda.core.contracts.StateAndRef;
import net.corda.core.contracts.UniqueIdentifier;
import net.corda.core.crypto.SecureHash;
import net.corda.core.flows.*;
import net.corda.core.identity.Party;
import net.corda.core.node.services.vault.QueryCriteria;
import net.corda.core.transactions.SignedTransaction;
import net.corda.core.transactions.TransactionBuilder;
import net.corda.core.utilities.ProgressTracker;
import net.corda.core.utilities.ProgressTracker.Step;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static net.corda.core.contracts.ContractsDSL.requireThat;

public interface TransferFlows {

    @InitiatingFlow
    @StartableByRPC
    class TransferInitiator extends FlowLogic<SignedTransaction> {
        @NotNull
        private final UniqueIdentifier linearId;
        @NotNull
        private final Party newOwner;
        @NotNull
        private final ProgressTracker progressTracker;
        private final static Step GENERATING_TRANSACTION = new ProgressTracker.Step(
                "Generating transaction based on parameters.");
        private final static Step VERIFYING_TRANSACTION = new ProgressTracker.Step(
                "Verifying contract constraints.");
        private final static Step SIGNING_TRANSACTION = new ProgressTracker.Step(
                "Signing transaction with our private key.");
        public final static Step GATHERING_SIGNS = new ProgressTracker.Step(
                "Gathering the counter party's signature"){
            @Override
            public ProgressTracker childProgressTracker() {
                return CollectSignaturesFlow.Companion.tracker();
            }
        };
        public final static Step FINALISING_TRANSACTION = new Step(
                "Obtaining notary signature and recording transaction."){
            @Override
            public ProgressTracker childProgressTracker() {
                return FinalityFlow.Companion.tracker();
            }
        };

        public static ProgressTracker tracker() {
            return new ProgressTracker(GENERATING_TRANSACTION, VERIFYING_TRANSACTION, SIGNING_TRANSACTION
                    , GATHERING_SIGNS, FINALISING_TRANSACTION);
        }

        public TransferInitiator(@NotNull UniqueIdentifier linearId, @NotNull Party newOwner) {
            this.linearId = linearId;
            this.newOwner = newOwner;
            this.progressTracker = tracker();
        }

        @Suspendable
        @Override
        public SignedTransaction call() throws FlowException {
            progressTracker.setCurrentStep(GENERATING_TRANSACTION);
            final QueryCriteria assetCriteria = new QueryCriteria.LinearStateQueryCriteria().withUuid(
                    Collections.singletonList(linearId.getId())
            );
            final List<StateAndRef<TicketState>> asset = getServiceHub()
                    .getVaultService()
                    .queryBy(TicketState.class, assetCriteria).getStates();
            final StateAndRef<TicketState> inputState = asset.get(0);

            final Party notary = inputState.getState().getNotary();
            final Party inputIssuer = inputState.getState().getData().getIssuer();
            final Party oldOwner = inputState.getState().getData().getSpectator();
            final int inputSection = inputState.getState().getData().getSection();
            final UniqueIdentifier conservedLinearId = inputState.getState().getData().getLinearId();
            final TicketState outputState = new TicketState(inputIssuer, this.newOwner, inputSection, conservedLinearId);
            final Command<Transfer> commandTransfer = new Command<>(new Transfer()
                    , Arrays.asList(oldOwner.getOwningKey(), this.newOwner.getOwningKey()));
            
            final TransactionBuilder txBuilder = new TransactionBuilder(notary)
                    .addCommand(commandTransfer)
                    .addInputState(inputState)
                    .addOutputState(outputState, TicketContract.ID );
            
            progressTracker.setCurrentStep(VERIFYING_TRANSACTION);
            txBuilder.verify(getServiceHub());
            
            progressTracker.setCurrentStep(SIGNING_TRANSACTION);
            final SignedTransaction partlySignedTx = getServiceHub().signInitialTransaction(txBuilder);

            progressTracker.setCurrentStep(GATHERING_SIGNS);
            FlowSession otherOwnerSession;
            if (getOurIdentity().equals(oldOwner)){
                otherOwnerSession = initiateFlow(newOwner);
            } else {
                otherOwnerSession = initiateFlow(oldOwner);
            }
            final SignedTransaction fullSignedTx = subFlow(new CollectSignaturesFlow(partlySignedTx,
                    Arrays.asList(otherOwnerSession),
                    GATHERING_SIGNS.childProgressTracker()));
            progressTracker.setCurrentStep(FINALISING_TRANSACTION);
            final SignedTransaction notarised = subFlow(new FinalityFlow(fullSignedTx,
                    Arrays.asList(otherOwnerSession, initiateFlow(inputIssuer)),
                    FINALISING_TRANSACTION.childProgressTracker()));
            return notarised;
        }
    }

    @InitiatedBy(TransferInitiator.class)
    class TransferResponder extends FlowLogic<SignedTransaction> {
        @NotNull
        private final FlowSession otherOwnerSession;
        @NotNull
        private final ProgressTracker progressTracker;
        public final static Step SIGNING_TRANSACTION = new Step("About to sign transaction with our private key."){
            @Override
            public ProgressTracker childProgressTracker() {
                return SignTransactionFlow.Companion.tracker();
            }
        };
        public final static Step FINALISING_TRANSACTION = new Step("Waiting to record transaction."){
            @Override
            public ProgressTracker childProgressTracker() {
                return FinalityFlow.Companion.tracker();
            }
        };
        @NotNull
        public static ProgressTracker tracker() {
            return new ProgressTracker(SIGNING_TRANSACTION, FINALISING_TRANSACTION);
        }

        public TransferResponder(@NotNull FlowSession otherOwnerSession) {
            this(otherOwnerSession, tracker());
        }

        public TransferResponder(@NotNull FlowSession otherOwnerSession, @NotNull ProgressTracker progressTracker) {
            this.otherOwnerSession = otherOwnerSession;
            this.progressTracker = progressTracker;
        }

        @Suspendable
        @Override
        public SignedTransaction call() throws FlowException {
            progressTracker.setCurrentStep(SIGNING_TRANSACTION);

            class SignTxFlow extends SignTransactionFlow {
                private SignTxFlow(FlowSession otherPartyFlow, ProgressTracker progressTracker) {
                    super(otherPartyFlow, progressTracker);
                }

                @Override
                protected void checkTransaction(@NotNull SignedTransaction stx) throws FlowException {
                    requireThat ( req -> {
                        ContractState output = stx.getTx().getOutputs().get(0).getData();
                        TicketState ticket = (TicketState) output;
                        req.using("Only accepted ticket of section = 30", ticket.getSection() == 30);
                        return null;
                    });
                }
            }
            final SignTxFlow signTxFlow = new SignTxFlow(otherOwnerSession, SignTransactionFlow.Companion.tracker());
            final SecureHash txId = subFlow(signTxFlow).getId();

            progressTracker.setCurrentStep(FINALISING_TRANSACTION);
            return subFlow(new ReceiveFinalityFlow(otherOwnerSession, txId));
        }
    }
}
