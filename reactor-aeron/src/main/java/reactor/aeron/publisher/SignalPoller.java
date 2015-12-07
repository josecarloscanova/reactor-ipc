/*
 * Copyright (c) 2011-2016 Pivotal Software Inc, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package reactor.aeron.publisher;

import org.reactivestreams.Subscriber;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.aeron.Context;
import reactor.aeron.support.AeronInfra;
import reactor.aeron.support.AeronUtils;
import reactor.aeron.support.DemandTracker;
import reactor.aeron.support.SignalType;
import reactor.core.error.Exceptions;
import reactor.fn.Consumer;
import reactor.io.buffer.Buffer;
import uk.co.real_logic.aeron.FragmentAssembler;
import uk.co.real_logic.aeron.Subscription;
import uk.co.real_logic.aeron.logbuffer.FragmentHandler;
import uk.co.real_logic.aeron.logbuffer.Header;
import uk.co.real_logic.agrona.DirectBuffer;
import uk.co.real_logic.agrona.concurrent.IdleStrategy;

/**
 * Signals receiver functionality which polls for signals sent by senders
 */
public class SignalPoller implements Runnable {

	private static final Logger logger = LoggerFactory.getLogger(SignalPoller.class);

	private final Subscriber<? super Buffer> subscriber;

	private final AeronPublisherSubscription downstreamSubscription;

	private final AeronInfra aeronInfra;

	private final Consumer<Boolean> shutdownTask;

	private final Context context;

	private final ErrorFragmentHandler errorFragmentHandler;

	private final CompleteNextFragmentHandler completeNextFragmentHandler;

	private volatile boolean running;

	private abstract class SignalPollerFragmentHandler implements FragmentHandler {

		private final FragmentAssembler fragmentAssembler = new FragmentAssembler(new FragmentHandler() {
			@Override
			public void onFragment(DirectBuffer buffer, int offset, int length, Header header) {
				doOnFragment(buffer, offset, length, header);
			}
		});

		@Override
		public void onFragment(DirectBuffer buffer, int offset, int length, Header header) {
			fragmentAssembler.onFragment(buffer, offset, length, header);
		}

		private void doOnFragment(DirectBuffer buffer, int offset, int length, Header header) {
			byte[] data = new byte[length - 1];
			buffer.getBytes(offset + 1, data);
			byte signalTypeCode = buffer.getByte(offset);
			try {
				if (!handleSignal(signalTypeCode, data, header.sessionId())) {
					logger.error("Message with unknown signal type code of {} and length of {} was ignored",
							signalTypeCode, data.length);
				}
			} catch (Throwable t) {
				Exceptions.throwIfFatal(t);
				subscriber.onError(t);
			}
		}

		/**
		 * Handles signal with type code of <code>signalTypeCode</code> and
		 * content of <code>data</code>
		 *
		 * @param signalTypeCode signal type code
		 * @param data signal data
		 * @param sessionId Aeron sessionId
		 * @return true if signal was handled and false otherwise
		 */
		abstract boolean handleSignal(byte signalTypeCode, byte[] data, int sessionId);

	}

	/**
	 * Handler for Complete and Next signals
	 */
	private class CompleteNextFragmentHandler extends SignalPollerFragmentHandler {

		/**
		 * If should read a single message from Aeron.
		 * Used to check if Complete signal was sent before any events were
		 * requested via a subscription
		 */
		boolean checkForComplete;

		/**
		 * Message read from Aeron but not yet forwarded into a subscriber
		 */
		Buffer reservedNextSignal;

		/**
		 * Number of Next signals received
		 */
		int nextSignalCounter;

		/**
		 * Complete signal was received from one of senders
		 */
		private boolean completeReceived = false;

		@Override
		boolean handleSignal(byte signalTypeCode, byte[] data, int sessionId) {
			if (signalTypeCode == SignalType.Next.getCode()) {
				Buffer buffer = Buffer.wrap(data);
				if (checkForComplete) {
					reservedNextSignal = buffer;
				} else {
					subscriber.onNext(buffer);
					nextSignalCounter++;
				}
			} else if (signalTypeCode == SignalType.Complete.getCode()) {
				completeReceived = true;
			} else {
				return false;
			}
			return true;
		}

		int getAndResetNextSignalCounter() {
			int result = nextSignalCounter;
			nextSignalCounter = 0;
			return result;
		}

		public boolean hasReservedNextSignal() {
			return reservedNextSignal != null;
		}

		public void processReservedNextSignal() {
			subscriber.onNext(reservedNextSignal);
			reservedNextSignal = null;
		}

		public boolean isCompleteReceived() {
			return completeReceived;
		}
	}

	/**
	 * Handler for Error signals
	 */
	private class ErrorFragmentHandler extends SignalPollerFragmentHandler {

		/**
		 * Error signal was received from one of senders
		 */
		private boolean errorReceived = false;

		@Override
		boolean handleSignal(byte signalTypeCode, byte[] data, int sessionId) {
			if (signalTypeCode == SignalType.Error.getCode()) {
				Throwable t = context.exceptionSerializer().deserialize(data);
				subscriber.onError(t);

				errorReceived = true;
				return true;
			}
			return false;
		}

		boolean isErrorReceived() {
			return errorReceived;
		}

	}

	public SignalPoller(Context context,
						Subscriber<? super Buffer> subscriber,
						AeronPublisherSubscription downstreamSubscription,
						AeronInfra aeronInfra,
						Consumer<Boolean> shutdownTask) {
		this.context = context;
		this.subscriber = subscriber;
		this.downstreamSubscription = downstreamSubscription;
		this.aeronInfra = aeronInfra;
		this.shutdownTask = shutdownTask;
		this.errorFragmentHandler = new ErrorFragmentHandler();
		this.completeNextFragmentHandler = new CompleteNextFragmentHandler();
	}

	@Override
	public void run() {
		running = true;
		logger.debug("Signal poller started");

		uk.co.real_logic.aeron.Subscription nextCompleteSub = createNextCompleteSub();
		uk.co.real_logic.aeron.Subscription errorSub = createErrorSub();

		setSubscriberSubscription();

		final IdleStrategy idleStrategy = AeronUtils.newBackoffIdleStrategy();
		final DemandTracker demandTracker = downstreamSubscription.getDemandTracker();
		boolean isTerminalSignalReceived = false;
		long demand = 0;
		try {
			while (running && downstreamSubscription.isActive()) {
				errorSub.poll(errorFragmentHandler, 1);
				if (errorFragmentHandler.isErrorReceived()) {
					isTerminalSignalReceived = true;
					break;
				}

				if (demand == 0) {
					demand = demandTracker.getAndReset();
				}

				int nFragmentsReceived = 0;
				int fragmentLimit = (int) Math.min(demand, context.signalPollerFragmentLimit());
				if (fragmentLimit == 0) {
					if (!completeNextFragmentHandler.hasReservedNextSignal()) {
						checkForCompleteSignal(nextCompleteSub);
					}
				} else {
					if (completeNextFragmentHandler.hasReservedNextSignal()) {
						completeNextFragmentHandler.processReservedNextSignal();
						fragmentLimit--;
						demand--;
					}
					if (fragmentLimit > 0) {
						nFragmentsReceived = nextCompleteSub.poll(completeNextFragmentHandler, fragmentLimit);
						demand -= completeNextFragmentHandler.getAndResetNextSignalCounter();
					}
				}
				idleStrategy.idle(nFragmentsReceived);

				if (completeNextFragmentHandler.isCompleteReceived()) {
					isTerminalSignalReceived = true;
					subscriber.onComplete();
					break;
				}
			}
		} finally {
			aeronInfra.close(nextCompleteSub);
			aeronInfra.close(errorSub);

			logger.trace("about to execute shutdownTask");
			shutdownTask.accept(isTerminalSignalReceived);
		}

		logger.debug("Signal poller shutdown");
	}

	private void setSubscriberSubscription() {
		//TODO: Possible timing issue due to ServiceMessagePoller termination
		try {
			subscriber.onSubscribe(downstreamSubscription);
		} catch (Throwable t) {
			Exceptions.throwIfFatal(t);
			subscriber.onError(t);
		}
	}

	private Subscription createErrorSub() {
		return aeronInfra.addSubscription(context.receiverChannel(), context.errorStreamId());
	}

	private Subscription createNextCompleteSub() {
		return aeronInfra.addSubscription(context.receiverChannel(), context.streamId());
	}

	private void checkForCompleteSignal(Subscription nextCompleteSub) {
		completeNextFragmentHandler.checkForComplete = true;
		nextCompleteSub.poll(completeNextFragmentHandler, 1);
		completeNextFragmentHandler.checkForComplete = false;
	}

	public void shutdown() {
		running = false;
	}

}